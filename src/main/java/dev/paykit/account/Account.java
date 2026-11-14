package dev.paykit.account;

import java.time.Instant;
import java.util.UUID;

public record Account(UUID id, String email, String name, String stripeCustomerId, boolean trialUsed,
                      Instant createdAt) {
}
