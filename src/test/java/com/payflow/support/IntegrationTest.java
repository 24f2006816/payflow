package com.payflow.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base class for integration / end-to-end tests: full application context, real infrastructure
 * (Testcontainers), real JWTs obtained from /v1/auth/token with the seeded demo clients.
 * Tests create their own wallets so they don't depend on each other's data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles({"seed", "local", "test"})
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    public static final UUID ACME_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID ACME_INR_ACCOUNT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    protected String adminToken() throws Exception {
        return token("admin-cli", "admin-secret-change-me");
    }

    protected String serviceToken() throws Exception {
        return token("checkout-service", "service-secret-change-me");
    }

    protected String merchantToken() throws Exception {
        return token("acme-merchant", "merchant-secret-change-me");
    }

    protected String token(String clientId, String secret) throws Exception {
        String body = mvc.perform(post("/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("client_id", clientId, "client_secret", secret))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("access_token").asText();
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected static String newKey() {
        return "test-" + UUID.randomUUID();
    }

    protected ResultActions postJson(String path, String token, String idempotencyKey, Object body) throws Exception {
        var req = post(path).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "" : json.writeValueAsString(body));
        if (idempotencyKey != null) {
            req.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(req);
    }

    protected JsonNode getJson(String path, String token) throws Exception {
        String body = mvc.perform(get(path).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    protected JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Opens a fresh INR wallet and funds it through the (simulated) payment provider. */
    protected UUID fundedWallet(long amountMinor) throws Exception {
        String svc = serviceToken();
        JsonNode wallet = read(postJson("/v1/accounts/wallets", svc, null,
                Map.of("customerId", UUID.randomUUID(), "currency", "INR")).andExpect(status().isCreated()));
        UUID walletId = UUID.fromString(wallet.get("id").asText());
        if (amountMinor > 0) {
            postJson("/v1/topups", svc, newKey(), Map.of("accountId", walletId, "amountMinor", amountMinor,
                    "currency", "INR")).andExpect(status().isCreated());
        }
        return walletId;
    }

    public record TestMerchant(UUID id, UUID inrAccount, String webhookSecret) {
    }

    /** A fresh merchant with a zero INR balance, so balance assertions are exact and order-independent. */
    protected TestMerchant newMerchant(String webhookUrl) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", "Test merchant " + UUID.randomUUID(),
                "currencies", java.util.List.of("INR")));
        if (webhookUrl != null) {
            body.put("webhookUrl", webhookUrl);
        }
        JsonNode m = read(postJson("/v1/merchants", adminToken(), null, body).andExpect(status().isCreated()));
        return new TestMerchant(UUID.fromString(m.get("id").asText()),
                UUID.fromString(m.get("accounts").get(0).get("id").asText()), m.get("webhookSecret").asText());
    }

    protected long balance(UUID accountId) throws Exception {
        return getJson("/v1/accounts/" + accountId, adminToken()).get("balanceMinor").asLong();
    }
}
