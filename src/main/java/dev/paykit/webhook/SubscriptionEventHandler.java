package dev.paykit.webhook;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import dev.paykit.account.Account;
import dev.paykit.account.AccountRepository;
import dev.paykit.billing.SubscriptionRecord;
import dev.paykit.billing.SubscriptionRepository;
import dev.paykit.catalog.CatalogRepository;
import dev.paykit.catalog.PlanPrice;
import dev.paykit.dunning.DunningService;
import dev.paykit.entitlement.EntitlementService;
import dev.paykit.outbox.OutboxService;

import org.springframework.stereotype.Component;

/**
 * {@code customer.subscription.created|updated|deleted}: upserts the local subscription, recomputes entitlements and
 * queues customer notifications. Events older than the last one applied are skipped (Stripe does not guarantee
 * delivery order).
 */
@Component
public class SubscriptionEventHandler implements StripeEventHandler {

    private final AccountResolver resolver;
    private final AccountRepository accounts;
    private final SubscriptionRepository subscriptions;
    private final CatalogRepository catalog;
    private final EntitlementService entitlements;
    private final DunningService dunning;
    private final OutboxService outbox;

    public SubscriptionEventHandler(AccountResolver resolver, AccountRepository accounts,
                                    SubscriptionRepository subscriptions, CatalogRepository catalog,
                                    EntitlementService entitlements, DunningService dunning, OutboxService outbox) {
        this.resolver = resolver;
        this.accounts = accounts;
        this.subscriptions = subscriptions;
        this.catalog = catalog;
        this.entitlements = entitlements;
        this.dunning = dunning;
        this.outbox = outbox;
    }

    @Override
    public Set<String> types() {
        return Set.of("customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted",
                "customer.subscription.trial_will_end");
    }

    @Override
    public EventOutcome handle(StripeEvent ev) {
        JsonNode obj = ev.object();
        Optional<Account> found = resolver.resolve(obj);
        if (found.isEmpty()) {
            return EventOutcome.ignored("no local account for customer " + StripeEvent.text(obj, "customer"));
        }
        UUID accountId = found.get().id();
        String subId = obj.path("id").asText();

        if (ev.type().endsWith("trial_will_end")) {
            outbox.enqueue("trial.ending", accountId, "trial-ending-" + subId, Map.of(
                    "subject", "Your free trial ends on " + day(epoch(obj, "trial_end")),
                    "subscription", subId, "trialEnd", String.valueOf(epoch(obj, "trial_end"))));
            return EventOutcome.processed(accountId, "trial ending notice queued");
        }

        Optional<SubscriptionRecord> existing = subscriptions.lockByAccount(accountId);
        if (existing.isPresent() && existing.get().stripeSubscriptionId().equals(subId)
                && ev.created().isBefore(existing.get().lastEventCreated())) {
            return EventOutcome.processed(accountId, "stale: older than the last applied event, skipped");
        }
        if (existing.isPresent() && !existing.get().stripeSubscriptionId().equals(subId)
                && existing.get().isChangeable() && ev.type().endsWith("deleted")) {
            return EventOutcome.ignored(accountId, "deleted subscription is not the account's current one");
        }

        JsonNode item = obj.path("items").path("data").path(0);
        JsonNode price = item.path("price");
        String lookupKey = StripeEvent.text(price, "lookup_key");
        PlanPrice planPrice = (lookupKey != null ? catalog.price(lookupKey) : Optional.<PlanPrice>empty())
                .or(() -> catalog.priceByStripeId(price.path("id").asText()))
                .orElseThrow(() -> new IllegalStateException("Price " + price.path("id").asText()
                        + " (lookup_key " + lookupKey + ") is not in the PayKit catalog"));

        String status = ev.type().endsWith("deleted") ? "canceled" : obj.path("status").asText();
        // API 2025-03-31+: period lives on the item; older versions have it on the subscription.
        Instant periodEnd = Optional.ofNullable(epoch(item, "current_period_end"))
                .orElse(epoch(obj, "current_period_end"));
        SubscriptionRecord updated = new SubscriptionRecord(existing.map(SubscriptionRecord::id).orElse(UUID.randomUUID()),
                accountId, subId, StripeEvent.text(item, "id"), StripeEvent.text(obj, "customer"),
                planPrice.planCode(), planPrice.lookupKey(), status, epoch(obj, "trial_end"), periodEnd,
                obj.path("cancel_at_period_end").asBoolean(false), epoch(obj, "canceled_at"), ev.created(), null);
        subscriptions.upsert(updated);
        if (updated.trialEnd() != null) {
            accounts.markTrialUsed(accountId);
        }

        String previousStatus = existing.filter(s -> s.stripeSubscriptionId().equals(subId))
                .map(SubscriptionRecord::status).orElse(null);
        String previousPlan = existing.filter(s -> s.stripeSubscriptionId().equals(subId))
                .map(SubscriptionRecord::priceLookupKey).orElse(null);
        boolean wasCancelling = existing.filter(s -> s.stripeSubscriptionId().equals(subId))
                .map(SubscriptionRecord::cancelAtPeriodEnd).orElse(false);

        if ("canceled".equals(status) || "unpaid".equals(status)) {
            dunning.lose(accountId);
        } else if ("active".equals(status) && "past_due".equals(previousStatus)) {
            dunning.recover(accountId);
        }
        var view = entitlements.recompute(accountId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subscription", subId);
        payload.put("plan", planPrice.planCode());
        payload.put("status", status);
        String note;
        if (ev.type().endsWith("created")) {
            payload.put("subject", "trialing".equals(status)
                    ? "Welcome! Your " + planPrice.planCode() + " trial has started"
                    : "Welcome! Your " + planPrice.planCode() + " subscription is active");
            outbox.enqueue("subscription.started", accountId, "sub-started-" + subId, payload);
            note = "subscription " + status + " on " + planPrice.lookupKey();
        } else if (ev.type().endsWith("deleted")) {
            payload.put("subject", "Your subscription has ended");
            outbox.enqueue("subscription.ended", accountId, "sub-ended-" + subId, payload);
            note = "subscription ended, entitlements -> " + view.plan();
        } else if (updated.cancelAtPeriodEnd() && !wasCancelling) {
            payload.put("subject", "Your subscription will end on " + day(periodEnd));
            outbox.enqueue("subscription.cancel_scheduled", accountId, "cancel-scheduled-" + ev.id(), payload);
            note = "cancel at period end scheduled (" + day(periodEnd) + ")";
        } else if (previousPlan != null && !previousPlan.equals(planPrice.lookupKey())) {
            payload.put("subject", "Your plan changed to " + planPrice.planCode());
            payload.put("from", previousPlan);
            outbox.enqueue("subscription.plan_changed", accountId, "plan-changed-" + ev.id(), payload);
            note = "plan " + previousPlan + " -> " + planPrice.lookupKey();
        } else if (previousStatus != null && !previousStatus.equals(status)) {
            note = "status " + previousStatus + " -> " + status + ", entitlements -> " + view.plan();
        } else {
            note = wasCancelling && !updated.cancelAtPeriodEnd() ? "cancellation withdrawn" : "subscription " + status;
        }
        return EventOutcome.processed(accountId, note);
    }

    private static final java.time.format.DateTimeFormatter DAY =
            java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH)
                    .withZone(java.time.ZoneOffset.UTC);

    static String day(Instant i) {
        return i == null ? "?" : DAY.format(i);
    }

    static Instant epoch(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? Instant.ofEpochSecond(v.asLong()) : null;
    }
}
