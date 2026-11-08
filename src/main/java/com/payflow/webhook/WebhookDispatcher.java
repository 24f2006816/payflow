package com.payflow.webhook;

import com.payflow.config.PayflowProperties;
import com.payflow.domain.retry.RetryBackoff;
import com.payflow.domain.security.WebhookSigner;
import com.payflow.merchant.MerchantEntity;
import com.payflow.merchant.MerchantService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Delivers webhooks over HTTP with HMAC signatures, retries with capped exponential backoff + jitter, and
 * marks deliveries DEAD after max attempts (they can be inspected and replayed by ops).
 * <p>
 * Claiming: one UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING pushes next_attempt_at
 * forward (a lease) and returns the rows. The HTTP call then happens OUTSIDE any transaction, so a slow merchant
 * endpoint never holds a database connection. If this instance dies mid-send the lease expires and another
 * instance retries: delivery is at-least-once, and merchants de-duplicate on the PayFlow-Event-Id header.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final int LEASE_SECONDS = 60;

    private final JdbcTemplate jdbc;
    private final RestClient http;
    private final MerchantService merchants;
    private final RetryBackoff backoff;
    private final PayflowProperties props;
    private final Clock clock;
    private final MeterRegistry meters;

    public WebhookDispatcher(JdbcTemplate jdbc, RestClient webhookRestClient, MerchantService merchants,
                             RetryBackoff webhookBackoff, PayflowProperties props, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.http = webhookRestClient;
        this.merchants = merchants;
        this.backoff = webhookBackoff;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
    }

    record Delivery(UUID id, UUID merchantId, UUID eventId, String eventType, String payload, int attempts) {
    }

    @Scheduled(fixedDelayString = "${payflow.webhooks.poll-interval}")
    public void dispatchDue() {
        for (Delivery d : claimDue()) {
            deliver(d);
        }
    }

    List<Delivery> claimDue() {
        return jdbc.query("""
                        UPDATE webhook_deliveries
                        SET next_attempt_at = now() + make_interval(secs => ?), updated_at = now()
                        WHERE id IN (
                            SELECT id FROM webhook_deliveries
                            WHERE status = 'PENDING' AND next_attempt_at <= now()
                            ORDER BY next_attempt_at
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED)
                        RETURNING id, merchant_id, event_id, event_type, payload::text AS payload, attempts
                        """,
                (rs, i) -> new Delivery(rs.getObject("id", UUID.class), rs.getObject("merchant_id", UUID.class),
                        rs.getObject("event_id", UUID.class), rs.getString("event_type"), rs.getString("payload"),
                        rs.getInt("attempts")),
                LEASE_SECONDS, props.webhooks().batchSize());
    }

    void deliver(Delivery d) {
        MerchantEntity merchant = merchants.getWithSecret(d.merchantId());
        if (merchant.getWebhookUrl() == null) {
            markDead(d, null, "merchant has no webhook url");
            return;
        }
        Instant now = clock.instant();
        Integer statusCode = null;
        String error;
        try {
            var response = http.post()
                    .uri(merchant.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("PayFlow-Event-Id", d.eventId().toString())
                    .header("PayFlow-Event-Type", d.eventType())
                    .header("PayFlow-Signature", WebhookSigner.signatureHeader(merchant.getWebhookSecret(), now, d.payload()))
                    .body(d.payload())
                    .retrieve()
                    .toBodilessEntity();
            statusCode = response.getStatusCode().value();
            if (response.getStatusCode().is2xxSuccessful()) {
                jdbc.update("""
                        UPDATE webhook_deliveries SET status = 'SUCCEEDED', attempts = attempts + 1,
                               last_status_code = ?, last_error = NULL, updated_at = now() WHERE id = ?
                        """, statusCode, d.id());
                meters.counter("payflow.webhooks", "outcome", "succeeded").increment();
                return;
            }
            error = "non-2xx response " + statusCode;
        } catch (RestClientResponseException e) {
            statusCode = e.getStatusCode().value();
            error = "HTTP " + statusCode;
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        int attempts = d.attempts() + 1;
        if (backoff.exhausted(attempts)) {
            markDead(d, statusCode, error);
            return;
        }
        Instant next = now.plus(backoff.nextDelay(attempts));
        jdbc.update("""
                UPDATE webhook_deliveries SET attempts = ?, next_attempt_at = ?, last_status_code = ?,
                       last_error = ?, updated_at = now() WHERE id = ?
                """, attempts, Timestamp.from(next), statusCode, truncate(error), d.id());
        meters.counter("payflow.webhooks", "outcome", "retry_scheduled").increment();
        log.info("webhook {} attempt {} failed ({}), next attempt at {}", d.id(), attempts, error, next);
    }

    private void markDead(Delivery d, Integer statusCode, String error) {
        jdbc.update("""
                UPDATE webhook_deliveries SET status = 'DEAD', attempts = attempts + 1, last_status_code = ?,
                       last_error = ?, updated_at = now() WHERE id = ?
                """, statusCode, truncate(error), d.id());
        meters.counter("payflow.webhooks", "outcome", "dead").increment();
        log.warn("webhook {} for event {} is DEAD: {}", d.id(), d.eventId(), error);
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
