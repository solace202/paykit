package dev.paykit.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("paykit")
public record PayKitProperties(String publicUrl, Stripe stripe, Billing billing, Events events, Outbox outbox,
                               Admin admin, Demo demo) {

    public record Stripe(String apiKey, String apiBase, String webhookSecret, Duration webhookTolerance,
                         boolean syncCatalogOnStartup) {
    }

    public record Billing(Duration gracePeriod, String downgradeProration) {
    }

    public record Events(int maxAttempts, Duration retryBaseDelay, Duration retryMaxDelay, Duration retryPollInterval) {
    }

    public record Outbox(int maxAttempts, Duration baseDelay, Duration maxDelay, int batchSize, Duration pollInterval,
                         String webhookUrl) {
    }

    public record Admin(String username, String password) {
    }

    public record Demo(boolean enabled, String webhookTarget) {
    }

    public String url(String path) {
        return publicUrl.replaceAll("/+$", "") + path;
    }
}
