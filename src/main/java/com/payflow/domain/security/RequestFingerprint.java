package com.payflow.domain.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 fingerprint of an HTTP request (method + path + canonical body).
 * Stored with an idempotency key so that reusing the key with a DIFFERENT request is rejected
 * instead of silently replaying the old response (the Stripe / IETF Idempotency-Key behaviour).
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(String method, String path, String canonicalBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(method.toUpperCase().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update((canonicalBody == null ? "" : canonicalBody).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
