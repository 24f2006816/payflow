package com.payflow.domain.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Signs webhook payloads the way Stripe does: header {@code PayFlow-Signature: t=<unix>,v1=<hex>}
 * where v1 = HMAC-SHA256(secret, "&lt;t&gt;.&lt;payload&gt;"). The timestamp inside the signed string
 * lets receivers reject replays older than a tolerance window.
 */
public final class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSigner() {
    }

    public static String signatureHeader(String secret, Instant timestamp, String payload) {
        long t = timestamp.getEpochSecond();
        return "t=" + t + ",v1=" + hmacHex(secret, t + "." + payload);
    }

    /** Receiver-side verification (used in tests and documented for merchants). Constant-time compare. */
    public static boolean verify(String secret, String header, String payload, Instant now, Duration tolerance) {
        if (header == null) {
            return false;
        }
        Long t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t == null || v1 == null) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - t) > tolerance.toSeconds()) {
            return false;
        }
        byte[] expected = hmacHex(secret, t + "." + payload).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, v1.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("cannot compute HMAC", e);
        }
    }
}
