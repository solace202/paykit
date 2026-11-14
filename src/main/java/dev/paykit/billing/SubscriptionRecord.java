package dev.paykit.billing;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Local projection of a Stripe subscription, written only from verified webhooks. */
public record SubscriptionRecord(UUID id, UUID accountId, String stripeSubscriptionId, String stripeItemId,
                                 String stripeCustomerId, String planCode, String priceLookupKey, String status,
                                 Instant trialEnd, Instant currentPeriodEnd, boolean cancelAtPeriodEnd,
                                 Instant canceledAt, Instant lastEventCreated, Instant updatedAt) {

    /** Stripe statuses a customer can still change plan or cancel from. */
    static final Set<String> CHANGEABLE = Set.of("trialing", "active", "past_due");

    public boolean isChangeable() {
        return CHANGEABLE.contains(status);
    }
}
