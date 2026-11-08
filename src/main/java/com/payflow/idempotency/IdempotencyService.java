package com.payflow.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.payflow.common.ApiException;
import com.payflow.config.PayflowProperties;
import com.payflow.domain.security.RequestFingerprint;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * HTTP-level idempotency (the Stripe "Idempotency-Key" pattern).
 *
 * <pre>
 *  1. claim   : INSERT key (IN_PROGRESS, request fingerprint, lock lease)  -- own transaction, commits now
 *               conflict? same fingerprint+COMPLETED -> replay stored response
 *                         different fingerprint      -> 422 (key reused for another request)
 *                         IN_PROGRESS, lease valid   -> 409 (concurrent duplicate)
 *                         IN_PROGRESS, lease expired -> take over (previous attempt crashed)
 *  2. execute : run the business operation (its own transaction)
 *  3. complete: store status + body                                        -- own transaction
 *     failure : delete the key so the client may retry with the same key
 * </pre>
 *
 * This layer makes retries return the SAME response. It is deliberately not the only protection:
 * money-moving tables also carry UNIQUE (client_id, idempotency_key), so even if a crash happens between
 * steps 2 and 3, a retried request finds the existing payment instead of charging twice.
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z0-9_.:-]{8,255}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;
    private final ObjectMapper objectMapper;
    private final ObjectMapper canonicalMapper;
    private final PayflowProperties props;
    private final Clock clock;
    private final MeterRegistry meters;

    public IdempotencyService(JdbcTemplate jdbc, PlatformTransactionManager txManager, ObjectMapper objectMapper,
                              PayflowProperties props, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.objectMapper = objectMapper;
        // Canonical JSON (sorted keys) so {"a":1,"b":2} and {"b":2,"a":1} fingerprint identically.
        this.canonicalMapper = JsonMapper.builder()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .findAndAddModules()
                .build();
        this.props = props;
        this.clock = clock;
        this.meters = meters;
    }

    public static void requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("idempotency_key_missing", "the " + HEADER + " header is required");
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw ApiException.badRequest("idempotency_key_invalid",
                    HEADER + " must be 8-255 characters of [A-Za-z0-9_.:-]");
        }
    }

    public String fingerprint(String method, String path, Object body) {
        try {
            return RequestFingerprint.of(method, path, body == null ? "" : canonicalMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot fingerprint request", e);
        }
    }

    public ResponseEntity<?> execute(String clientId, String key, String fingerprint,
                                     Supplier<ResponseEntity<?>> operation) {
        requireValidKey(key);
        ResponseEntity<?> replay = requiresNew.execute(status -> claim(clientId, key, fingerprint));
        if (replay != null) {
            meters.counter("payflow.idempotency.replayed").increment();
            return replay;
        }
        ResponseEntity<?> response;
        try {
            response = operation.get();
        } catch (RuntimeException e) {
            requiresNew.executeWithoutResult(status -> jdbc.update(
                    "DELETE FROM idempotency_keys WHERE client_id = ? AND idempotency_key = ?", clientId, key));
            throw e;
        }
        String body = toJson(response.getBody());
        requiresNew.executeWithoutResult(status -> jdbc.update("""
                        UPDATE idempotency_keys
                        SET status = 'COMPLETED', response_status = ?, response_body = ?::jsonb, completed_at = now()
                        WHERE client_id = ? AND idempotency_key = ?
                        """, response.getStatusCode().value(), body, clientId, key));
        return response;
    }

    /** @return a replayable response, or null if this call now owns the key and must execute. */
    private ResponseEntity<?> claim(String clientId, String key, String fingerprint) {
        Instant now = clock.instant();
        Timestamp lease = Timestamp.from(now.plus(props.idempotency().lockTimeout()));
        int inserted = jdbc.update("""
                INSERT INTO idempotency_keys (client_id, idempotency_key, request_hash, status, locked_until)
                VALUES (?, ?, ?, 'IN_PROGRESS', ?)
                ON CONFLICT (client_id, idempotency_key) DO NOTHING
                """, clientId, key, fingerprint, lease);
        if (inserted == 1) {
            return null;
        }
        var rows = jdbc.queryForList("""
                SELECT request_hash, status, response_status, response_body::text AS body, locked_until
                FROM idempotency_keys WHERE client_id = ? AND idempotency_key = ? FOR UPDATE
                """, clientId, key);
        if (rows.isEmpty()) {
            // The original attempt failed and released the key between our INSERT and SELECT.
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_in_progress",
                    "a request with this " + HEADER + " was just processed concurrently; retry");
        }
        var existing = rows.get(0);
        if (!fingerprint.equals(existing.get("request_hash"))) {
            throw ApiException.unprocessable("idempotency_key_reused",
                    "this " + HEADER + " was already used for a different request");
        }
        if ("COMPLETED".equals(existing.get("status"))) {
            int statusCode = ((Number) existing.get("response_status")).intValue();
            return ResponseEntity.status(statusCode)
                    .header("Idempotent-Replayed", "true")
                    .body(fromJson((String) existing.get("body")));
        }
        Instant lockedUntil = ((Timestamp) existing.get("locked_until")).toInstant();
        if (lockedUntil.isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_in_progress",
                    "a request with this " + HEADER + " is still being processed; retry later");
        }
        // Previous holder crashed: take over the key with a fresh lease.
        jdbc.update("UPDATE idempotency_keys SET locked_until = ? WHERE client_id = ? AND idempotency_key = ?",
                lease, clientId, key);
        return null;
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot store idempotent response", e);
        }
    }

    private JsonNode fromJson(String body) {
        try {
            return objectMapper.readTree(body == null ? "null" : body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt idempotent response", e);
        }
    }
}
