package dev.paykit.webhook;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import dev.paykit.account.Account;
import dev.paykit.dunning.DunningCase;
import dev.paykit.dunning.DunningService;
import dev.paykit.entitlement.EntitlementService;
import dev.paykit.outbox.OutboxService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** {@code invoice.paid} closes a dunning case and sends a receipt; {@code invoice.payment_failed} opens/updates one. */
@Component
public class InvoiceEventHandler implements StripeEventHandler {

    private final AccountResolver resolver;
    private final JdbcClient jdbc;
    private final DunningService dunning;
    private final EntitlementService entitlements;
    private final OutboxService outbox;

    public InvoiceEventHandler(AccountResolver resolver, JdbcClient jdbc, DunningService dunning,
                               EntitlementService entitlements, OutboxService outbox) {
        this.resolver = resolver;
        this.jdbc = jdbc;
        this.dunning = dunning;
        this.entitlements = entitlements;
        this.outbox = outbox;
    }

    @Override
    public Set<String> types() {
        return Set.of("invoice.paid", "invoice.payment_failed");
    }

    @Override
    public EventOutcome handle(StripeEvent ev) {
        JsonNode inv = ev.object();
        JsonNode subDetails = inv.path("parent").path("subscription_details");
        Optional<Account> found = resolver.resolve(inv, subDetails.path("metadata"));
        if (found.isEmpty()) {
            return EventOutcome.ignored("no local account for customer " + StripeEvent.text(inv, "customer"));
        }
        UUID accountId = found.get().id();
        String invoiceId = inv.path("id").asText();
        // API 2025-03-31+: parent.subscription_details.subscription; older versions: invoice.subscription
        String subscriptionId = Optional.ofNullable(StripeEvent.text(subDetails, "subscription"))
                .orElse(StripeEvent.text(inv, "subscription"));
        int attempt = inv.path("attempt_count").asInt(0);
        Instant nextAttempt = SubscriptionEventHandler.epoch(inv, "next_payment_attempt");
        long amountDue = inv.path("amount_due").asLong();
        long amountPaid = inv.path("amount_paid").asLong();
        String currency = inv.path("currency").asText("usd");
        upsertInvoice(accountId, inv, subscriptionId, attempt, nextAttempt, ev.created());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoice", invoiceId);
        payload.put("amount", Money.format(ev.type().equals("invoice.paid") ? amountPaid : amountDue, currency));
        if (ev.type().equals("invoice.paid")) {
            boolean recovered = dunning.recover(accountId);
            entitlements.recompute(accountId);
            if (amountPaid > 0) {
                payload.put("subject", "Receipt for " + payload.get("amount"));
                payload.put("hostedInvoiceUrl", StripeEvent.text(inv, "hosted_invoice_url"));
                outbox.enqueue("invoice.receipt", accountId, "receipt-" + invoiceId, payload);
            }
            if (recovered) {
                outbox.enqueue("payment.recovered", accountId, "recovered-" + invoiceId,
                        Map.of("subject", "Thanks, your payment went through", "invoice", invoiceId));
            }
            return EventOutcome.processed(accountId, "paid " + payload.get("amount")
                    + (recovered ? ", dunning case recovered" : ""));
        }

        DunningCase c = dunning.recordFailure(accountId, invoiceId, attempt, ev.created(), nextAttempt);
        var view = entitlements.recompute(accountId);
        payload.put("subject", "Payment of " + payload.get("amount") + " failed, please update your card");
        payload.put("attempt", attempt);
        payload.put("nextAttempt", String.valueOf(nextAttempt));
        payload.put("graceUntil", c.graceUntil().toString());
        payload.put("hostedInvoiceUrl", StripeEvent.text(inv, "hosted_invoice_url"));
        outbox.enqueue("payment.failed", accountId, "payment-failed-" + invoiceId + "-" + attempt, payload);
        return EventOutcome.processed(accountId, "payment failed (attempt " + attempt + "), dunning open, grace until "
                + SubscriptionEventHandler.day(c.graceUntil()) + ", entitlements: " + view.plan());
    }

    private void upsertInvoice(UUID accountId, JsonNode inv, String subscriptionId, int attempt, Instant nextAttempt,
                               Instant eventCreated) {
        Instant created = Optional.ofNullable(SubscriptionEventHandler.epoch(inv, "created")).orElse(eventCreated);
        jdbc.sql("""
                insert into invoices (stripe_invoice_id, account_id, stripe_subscription_id, status, billing_reason,
                    amount_due, amount_paid, currency, attempt_count, next_payment_attempt, hosted_invoice_url, created)
                values (:id, :account, :sub, :status, :reason, :due, :paid, :currency, :attempt, :next, :url, :created)
                on conflict (stripe_invoice_id) do update set status = excluded.status,
                    amount_paid = excluded.amount_paid, attempt_count = greatest(invoices.attempt_count, excluded.attempt_count),
                    next_payment_attempt = excluded.next_payment_attempt, updated_at = now()""")
                .param("id", inv.path("id").asText()).param("account", accountId).param("sub", subscriptionId)
                .param("status", inv.path("status").asText("open")).param("reason", StripeEvent.text(inv, "billing_reason"))
                .param("due", inv.path("amount_due").asLong()).param("paid", inv.path("amount_paid").asLong())
                .param("currency", inv.path("currency").asText("usd")).param("attempt", attempt)
                .param("next", nextAttempt == null ? null : Timestamp.from(nextAttempt))
                .param("url", StripeEvent.text(inv, "hosted_invoice_url")).param("created", Timestamp.from(created))
                .update();
    }
}
