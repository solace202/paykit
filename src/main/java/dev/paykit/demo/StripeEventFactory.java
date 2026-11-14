package dev.paykit.demo;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stripe.Stripe;

/**
 * Builds Stripe-shaped event payloads (current API version layout: period on subscription items, invoice
 * subscription under {@code parent.subscription_details}). Used to play Stripe's part offline and in tests.
 */
public final class StripeEventFactory {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private StripeEventFactory() {
    }

    public static String id(String prefix) {
        StringBuilder sb = new StringBuilder(prefix).append('_');
        for (int i = 0; i < 18; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public record Sub(String id, String customer, UUID accountId, String itemId, String priceId, String lookupKey,
                      long unitAmount, String interval, String status, Instant trialEnd, Instant periodStart,
                      Instant periodEnd, boolean cancelAtPeriodEnd, Instant canceledAt) {

        public Sub withStatus(String s) {
            return new Sub(id, customer, accountId, itemId, priceId, lookupKey, unitAmount, interval, s, trialEnd,
                    periodStart, periodEnd, cancelAtPeriodEnd, canceledAt);
        }

        public Sub withPrice(String newPriceId, String newLookupKey, long newAmount, String newInterval) {
            return new Sub(id, customer, accountId, itemId, newPriceId, newLookupKey, newAmount, newInterval, status,
                    trialEnd, periodStart, periodEnd, cancelAtPeriodEnd, canceledAt);
        }

        public Sub withPeriod(Instant start, Instant end) {
            return new Sub(id, customer, accountId, itemId, priceId, lookupKey, unitAmount, interval, status, trialEnd,
                    start, end, cancelAtPeriodEnd, canceledAt);
        }

        public Sub withCancelAtPeriodEnd(boolean cancel) {
            return new Sub(id, customer, accountId, itemId, priceId, lookupKey, unitAmount, interval, status, trialEnd,
                    periodStart, periodEnd, cancel, canceledAt);
        }

        public Sub withCanceledAt(Instant at) {
            return new Sub(id, customer, accountId, itemId, priceId, lookupKey, unitAmount, interval, status, trialEnd,
                    periodStart, periodEnd, cancelAtPeriodEnd, at);
        }
    }

    public record Inv(String id, String customer, String subscriptionId, UUID accountId, String status, long amountDue,
                      long amountPaid, int attemptCount, Instant nextPaymentAttempt, String billingReason,
                      Instant created) {
    }

    public static ObjectNode price(String priceId, String lookupKey, long unitAmount, String interval) {
        ObjectNode p = F.objectNode().put("id", priceId).put("object", "price").put("currency", "usd")
                .put("unit_amount", unitAmount).put("lookup_key", lookupKey).put("type", "recurring");
        p.putObject("recurring").put("interval", interval).put("interval_count", 1);
        return p;
    }

    public static ObjectNode subscription(Sub s) {
        ObjectNode o = F.objectNode().put("id", s.id()).put("object", "subscription").put("customer", s.customer())
                .put("status", s.status()).put("currency", "usd").put("collection_method", "charge_automatically")
                .put("cancel_at_period_end", s.cancelAtPeriodEnd()).put("livemode", false);
        epoch(o, "trial_end", s.trialEnd());
        epoch(o, "canceled_at", s.canceledAt());
        epoch(o, "cancel_at", s.cancelAtPeriodEnd() ? s.periodEnd() : null);
        epoch(o, "start_date", s.periodStart());
        o.putObject("metadata").put("account_id", s.accountId().toString());
        ObjectNode items = o.putObject("items").put("object", "list");
        ArrayNode data = items.putArray("data");
        ObjectNode item = data.addObject().put("id", s.itemId()).put("object", "subscription_item").put("quantity", 1);
        epoch(item, "current_period_start", s.periodStart());
        epoch(item, "current_period_end", s.periodEnd());
        item.set("price", price(s.priceId(), s.lookupKey(), s.unitAmount(), s.interval()));
        return o;
    }

    public static ObjectNode invoice(Inv i) {
        ObjectNode o = F.objectNode().put("id", i.id()).put("object", "invoice").put("customer", i.customer())
                .put("status", i.status()).put("currency", "usd").put("amount_due", i.amountDue())
                .put("amount_paid", i.amountPaid()).put("amount_remaining", i.amountDue() - i.amountPaid())
                .put("attempt_count", i.attemptCount()).put("attempted", i.attemptCount() > 0)
                .put("billing_reason", i.billingReason())
                .put("hosted_invoice_url", "https://invoice.stripe.com/i/acct_demo/test_" + i.id());
        epoch(o, "created", i.created());
        epoch(o, "next_payment_attempt", i.nextPaymentAttempt());
        ObjectNode parent = o.putObject("parent").put("type", "subscription_details");
        ObjectNode details = parent.putObject("subscription_details").put("subscription", i.subscriptionId());
        details.putObject("metadata").put("account_id", i.accountId().toString());
        return o;
    }

    public static ObjectNode checkoutSession(String id, String customer, String subscriptionId, UUID accountId,
                                             UUID checkoutId, long amountTotal) {
        ObjectNode o = F.objectNode().put("id", id).put("object", "checkout.session").put("mode", "subscription")
                .put("status", "complete").put("payment_status", amountTotal == 0 ? "no_payment_required" : "paid")
                .put("customer", customer).put("subscription", subscriptionId)
                .put("client_reference_id", accountId.toString()).put("amount_total", amountTotal)
                .put("currency", "usd");
        ObjectNode meta = o.putObject("metadata").put("account_id", accountId.toString());
        if (checkoutId != null) {
            meta.put("checkout_id", checkoutId.toString());
        }
        return o;
    }

    public static ObjectNode event(String type, ObjectNode object, ObjectNode previousAttributes, Instant created) {
        ObjectNode e = F.objectNode().put("id", id("evt")).put("object", "event").put("type", type)
                .put("api_version", Stripe.API_VERSION).put("created", created.getEpochSecond())
                .put("livemode", false).put("pending_webhooks", 1);
        ObjectNode data = e.putObject("data");
        data.set("object", object);
        if (previousAttributes != null) {
            data.set("previous_attributes", previousAttributes);
        }
        e.putObject("request").putNull("id").putNull("idempotency_key");
        return e;
    }

    private static void epoch(ObjectNode o, String field, Instant value) {
        if (value == null) {
            o.putNull(field);
        } else {
            o.put(field, value.getEpochSecond());
        }
    }
}
