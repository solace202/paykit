package dev.paykit.webhook;

import java.time.Instant;
import java.util.UUID;

public record StoredEvent(String id, String type, String apiVersion, boolean livemode, Instant created, String payload,
                          UUID accountId, String status, String note, int attempts, int deliveries, String lastError,
                          Instant nextAttemptAt, Instant receivedAt, Instant processedAt) {
}
