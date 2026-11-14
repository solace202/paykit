package dev.paykit.demo;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Produces a {@code Stripe-Signature} header exactly like Stripe does:
 * {@code t=<unix>,v1=hex(HMAC_SHA256(secret, "<unix>.<payload>"))}. Used by the offline demo and the tests.
 */
public final class WebhookSigner {

    private WebhookSigner() {
    }

    public static String header(String payload, String secret, Instant timestamp) {
        long t = timestamp.getEpochSecond();
        return "t=" + t + ",v1=" + hmac(secret, t + "." + payload);
    }

    static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
