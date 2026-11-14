package dev.paykit.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OutboxMessage(long id, String topic, UUID accountId, String dedupKey, Map<String, Object> payload,
                            String status, int attempts, String lastError, Instant nextAttemptAt, Instant createdAt,
                            Instant sentAt) {
}
