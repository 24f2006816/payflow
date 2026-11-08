package com.payflow.domain.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSignerTest {

    private static final String SECRET = "whsec_test";
    private static final String PAYLOAD = "{\"type\":\"payment.captured\"}";
    private final Instant now = Instant.parse("2026-11-08T10:00:00Z");

    @Test
    void signsAndVerifies() {
        String header = WebhookSigner.signatureHeader(SECRET, now, PAYLOAD);
        assertTrue(header.startsWith("t=" + now.getEpochSecond() + ",v1="));
        assertTrue(WebhookSigner.verify(SECRET, header, PAYLOAD, now.plusSeconds(10), Duration.ofMinutes(5)));
    }

    @Test
    void rejectsTamperedPayloadWrongSecretAndOldTimestamp() {
        String header = WebhookSigner.signatureHeader(SECRET, now, PAYLOAD);
        assertFalse(WebhookSigner.verify(SECRET, header, PAYLOAD + " ", now, Duration.ofMinutes(5)));
        assertFalse(WebhookSigner.verify("other", header, PAYLOAD, now, Duration.ofMinutes(5)));
        assertFalse(WebhookSigner.verify(SECRET, header, PAYLOAD, now.plusSeconds(301), Duration.ofMinutes(5)));
        assertFalse(WebhookSigner.verify(SECRET, "garbage", PAYLOAD, now, Duration.ofMinutes(5)));
        assertFalse(WebhookSigner.verify(SECRET, null, PAYLOAD, now, Duration.ofMinutes(5)));
    }

    @Test
    void fingerprintIsStableAndSensitiveToEveryPart() {
        String f1 = RequestFingerprint.of("POST", "/v1/payments", "{\"a\":1}");
        assertEquals(f1, RequestFingerprint.of("post", "/v1/payments", "{\"a\":1}"));
        assertEquals(64, f1.length());
        assertNotEquals(f1, RequestFingerprint.of("POST", "/v1/payments", "{\"a\":2}"));
        assertNotEquals(f1, RequestFingerprint.of("POST", "/v1/topups", "{\"a\":1}"));
    }
}
