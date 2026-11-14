package dev.paykit.entitlement;

import java.time.Instant;
import java.util.Optional;

import dev.paykit.billing.SubscriptionRecord;
import dev.paykit.catalog.Plan;
import dev.paykit.dunning.DunningCase;

/** Which plan's features an account gets right now. Pure function, unit tested. */
public final class EntitlementPolicy {

    public record Decision(String planCode, String reason) {
    }

    private static final java.time.format.DateTimeFormatter DAY = java.time.format.DateTimeFormatter
            .ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH).withZone(java.time.ZoneOffset.UTC);

    private EntitlementPolicy() {
    }

    private static String day(Instant i) {
        return i == null ? "?" : DAY.format(i);
    }

    public static Decision decide(Optional<SubscriptionRecord> subscription, Optional<DunningCase> openCase,
                                  Instant now) {
        if (subscription.isEmpty()) {
            return new Decision(Plan.FREE, "no subscription");
        }
        SubscriptionRecord s = subscription.get();
        return switch (s.status()) {
            case "trialing" -> new Decision(s.planCode(), "trialing until " + day(s.trialEnd()));
            case "active" -> new Decision(s.planCode(), s.cancelAtPeriodEnd()
                    ? "active, cancels at period end " + day(s.currentPeriodEnd()) : "active");
            case "past_due" -> openCase.filter(c -> c.inGrace(now))
                    .map(c -> new Decision(s.planCode(), "past_due, grace period until " + day(c.graceUntil())))
                    .orElse(new Decision(Plan.FREE, "past_due, grace period over"));
            default -> new Decision(Plan.FREE, "subscription " + s.status());
        };
    }
}
