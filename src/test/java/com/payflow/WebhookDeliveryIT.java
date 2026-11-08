package com.payflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.payflow.domain.security.WebhookSigner;
import com.payflow.support.IntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full asynchronous path: payment -> outbox row -> relay -> Kafka -> consumer -> webhook_deliveries ->
 * dispatcher -> signed HTTP POST to a real local HTTP server. The server fails the first call to prove retries.
 */
class WebhookDeliveryIT extends IntegrationTest {

    record Received(String eventId, String type, String signature, String body) {
    }

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void startReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hooks", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int n = calls.incrementAndGet();
            if (n == 1) {
                exchange.sendResponseHeaders(503, -1); // first attempt fails -> must be retried
            } else {
                received.add(new Received(exchange.getRequestHeaders().getFirst("PayFlow-Event-Id"),
                        exchange.getRequestHeaders().getFirst("PayFlow-Event-Type"),
                        exchange.getRequestHeaders().getFirst("PayFlow-Signature"), body));
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopReceiver() {
        server.stop(0);
    }

    @Test
    @DisplayName("authorized payment produces a signed, verifiable webhook after one failed attempt")
    void signedWebhookWithRetry() throws Exception {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hooks";
        TestMerchant merchant = newMerchant(url);
        String merchantId = merchant.id().toString();
        String secret = merchant.webhookSecret();

        UUID wallet = fundedWallet(5_000);
        String paymentId = read(postJson("/v1/payments", serviceToken(), newKey(), Map.of("merchantId", merchantId,
                "customerAccountId", wallet, "amountMinor", 700, "currency", "INR"))
                .andExpect(status().isCreated())).get("id").asText();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(received).isNotEmpty());

        Received hook = received.get(0);
        assertThat(calls.get()).isGreaterThanOrEqualTo(2);
        assertThat(hook.type()).isEqualTo("payment.authorized");
        assertThat(WebhookSigner.verify(secret, hook.signature(), hook.body(), Instant.now(), Duration.ofMinutes(5)))
                .as("signature must verify with the merchant secret").isTrue();
        JsonNode event = json.readTree(hook.body());
        assertThat(event.get("id").asText()).isEqualTo(hook.eventId());
        assertThat(event.get("data").get("paymentId").asText()).isEqualTo(paymentId);
        assertThat(event.get("schemaVersion").asInt()).isEqualTo(1);
    }
}
