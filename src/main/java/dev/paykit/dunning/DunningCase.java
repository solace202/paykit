package dev.paykit.dunning;

import java.time.Instant;
import java.util.UUID;

public record DunningCase(UUID id, UUID accountId, String stripeInvoiceId, String status, int failedAttempts,
                          Instant firstFailedAt, Instant lastFailedAt, Instant nextAttemptAt, Instant graceUntil,
                          Instant closedAt) {

    public boolean inGrace(Instant now) {
        return "OPEN".equals(status) && now.isBefore(graceUntil);
    }
}
