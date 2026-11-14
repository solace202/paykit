package dev.paykit.demo;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.paykit.account.Account;
import dev.paykit.account.AccountRepository;
import dev.paykit.billing.CheckoutRepository;
import dev.paykit.billing.ProrationCalculator;
import dev.paykit.billing.SubscriptionRecord;
import dev.paykit.billing.SubscriptionRepository;
import dev.paykit.catalog.CatalogRepository;
import dev.paykit.catalog.PlanPrice;
import dev.paykit.dunning.DunningCase;
import dev.paykit.dunning.DunningRepository;
import dev.paykit.web.ConflictException;
import dev.paykit.web.NotFoundException;
import dev.paykit.webhook.StoredEvent;
import dev.paykit.webhook.StripeEventRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Demo only: plays Stripe's side of the lifecycle (checkout, renewals, failed payments, retries, cancellation)
 * by sending the app the same signed webhooks Stripe would send.
 */
@Component
@ConditionalOnProperty(name = "paykit.demo.enabled", havingValue = "true")
public class DemoStripeSimulator {

    private final DemoWebhookEmitter emitter;
    private final CheckoutRepository checkouts;
    private final AccountRepository accounts;
    private final SubscriptionRepository subscriptions;
    private final CatalogRepository catalog;
    private final DunningRepository dunning;
    private final StripeEventRepository events;

    public DemoStripeSimulator(DemoWebhookEmitter emitter, CheckoutRepository checkouts, AccountRepository accounts,
                               SubscriptionRepository subscriptions, CatalogRepository catalog,
                               DunningRepository dunning, StripeEventRepository events) {
        this.emitter = emitter;
        this.checkouts = checkouts;
        this.accounts = accounts;
        this.subscriptions = subscriptions;
        this.catalog = catalog;
        this.dunning = dunning;
        this.events = events;
    }

    static Instant plusInterval(Instant from, String interval) {
        return from.atOffset(ZoneOffset.UTC).plus(1, "year".equals(interval) ? ChronoUnit.YEARS : ChronoUnit.MONTHS)
                .toInstant();
    }

    static Instant minusInterval(Instant from, String interval) {
        return from.atOffset(ZoneOffset.UTC).minus(1, "year".equals(interval) ? ChronoUnit.YEARS : ChronoUnit.MONTHS)
                .toInstant();
    }

    /** The customer paid on the (demo) Checkout page. */
    public UUID completeCheckout(UUID checkoutId) {
        var session = checkouts.find(checkoutId).orElseThrow(() -> new NotFoundException("Checkout not found"));
        if (!"OPEN".equals(session.status())) {
            throw new ConflictException("Checkout session is " + session.status());
        }
        Account account = accounts.get(session.accountId());
        PlanPrice price = catalog.price(session.priceLookupKey()).orElseThrow();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        boolean trial = session.trialDays() > 0;
        Instant trialEnd = trial ? now.plus(Duration.ofDays(session.trialDays())) : null;
        Instant periodEnd = trial ? trialEnd : plusInterval(now, price.interval());
        String subId = StripeEventFactory.id("sub");
        var sub = new StripeEventFactory.Sub(subId, account.stripeCustomerId(), account.id(), StripeEventFactory.id("si"),
                price.stripePriceId(), price.lookupKey(), price.unitAmount(), price.interval(),
                trial ? "trialing" : "active", trialEnd, now, periodEnd, false, null);
        long charged = trial ? 0 : price.unitAmount();
        emitter.send(StripeEventFactory.event("checkout.session.completed", StripeEventFactory.checkoutSession(
                session.stripeSessionId(), account.stripeCustomerId(), subId, account.id(), checkoutId, charged), null, now));
        emitter.send(StripeEventFactory.event("customer.subscription.created", StripeEventFactory.subscription(sub),
                null, now));
        emitter.send(StripeEventFactory.event("invoice.paid", StripeEventFactory.invoice(new StripeEventFactory.Inv(
                StripeEventFactory.id("in"), account.stripeCustomerId(), subId, account.id(), "paid", charged, charged,
                charged > 0 ? 1 : 0, null, "subscription_create", now)), null, now));
        return account.id();
    }

    StripeEventFactory.Sub snapshot(SubscriptionRecord s) {
        PlanPrice p = catalog.price(s.priceLookupKey()).orElseThrow();
        Instant end = s.currentPeriodEnd();
        Instant start = s.trialEnd() != null && "trialing".equals(s.status())
                ? s.trialEnd().minus(Duration.ofDays(catalog.plan(p.planCode()).trialDays()))
                : minusInterval(end, p.interval());
        return new StripeEventFactory.Sub(s.stripeSubscriptionId(), s.stripeCustomerId(), s.accountId(),
                s.stripeItemId(), p.stripePriceId(), p.lookupKey(), p.unitAmount(), p.interval(), s.status(),
                s.trialEnd(), start, end, s.cancelAtPeriodEnd(), s.canceledAt());
    }

    private SubscriptionRecord current(UUID accountId) {
        return subscriptions.findByAccount(accountId)
                .orElseThrow(() -> new ConflictException("No subscription yet: subscribe on the pricing page first"));
    }

    /** Stripe confirms a plan change: subscription.updated (+ a proration invoice for immediate upgrades). */
    public void priceChanged(SubscriptionRecord s, PlanPrice to, String prorationBehavior) {
        Instant now = Instant.now();
        var before = snapshot(s);
        var after = before.withPrice(to.stripePriceId(), to.lookupKey(), to.unitAmount(), to.interval());
        long prorated = ProrationCalculator.prorate(before.unitAmount(), to.unitAmount(), before.periodStart(),
                before.periodEnd(), now);
        if (!before.interval().equals(to.interval()) && !"trialing".equals(s.status())) {
            // Interval change resets the billing cycle: credit the unused time, charge the new full period.
            prorated = to.unitAmount() - Math.round(before.unitAmount() * remaining(before, now));
            after = after.withPeriod(now, plusInterval(now, to.interval()));
        }
        ObjectNode prev = JsonNodeFactory.instance.objectNode();
        prev.putObject("items").putArray("data").add(StripeEventFactory.subscription(before).path("items").path("data").path(0));
        emitter.send(StripeEventFactory.event("customer.subscription.updated", StripeEventFactory.subscription(after),
                prev, now));
        if ("always_invoice".equals(prorationBehavior) && prorated > 0) {
            emitter.send(StripeEventFactory.event("invoice.paid", StripeEventFactory.invoice(new StripeEventFactory.Inv(
                    StripeEventFactory.id("in"), s.stripeCustomerId(), s.stripeSubscriptionId(), s.accountId(), "paid",
                    prorated, prorated, 1, null, "subscription_update", now)), null, now));
        }
    }

    private static double remaining(StripeEventFactory.Sub s, Instant now) {
        return (double) Duration.between(now, s.periodEnd()).getSeconds()
                / Duration.between(s.periodStart(), s.periodEnd()).getSeconds();
    }

    public void cancelFlagChanged(SubscriptionRecord s, boolean cancel) {
        ObjectNode prev = JsonNodeFactory.instance.objectNode().put("cancel_at_period_end", !cancel);
        emitter.send(StripeEventFactory.event("customer.subscription.updated",
                StripeEventFactory.subscription(snapshot(s).withCancelAtPeriodEnd(cancel)), prev, Instant.now()));
    }

    /** The trial is over and the first real invoice is paid. */
    public void endTrial(UUID accountId) {
        SubscriptionRecord s = current(accountId);
        if (!"trialing".equals(s.status())) {
            throw new ConflictException("Subscription is not trialing");
        }
        Instant now = Instant.now();
        var sub = snapshot(s);
        var active = sub.withStatus("active").withPeriod(now, plusInterval(now, sub.interval()));
        emitter.send(StripeEventFactory.event("customer.subscription.updated", StripeEventFactory.subscription(active),
                JsonNodeFactory.instance.objectNode().put("status", "trialing"), now));
        emitter.send(StripeEventFactory.event("invoice.paid", StripeEventFactory.invoice(new StripeEventFactory.Inv(
                StripeEventFactory.id("in"), s.stripeCustomerId(), s.stripeSubscriptionId(), accountId, "paid",
                sub.unitAmount(), sub.unitAmount(), 1, null, "subscription_cycle", now)), null, now));
    }

    public void trialWillEnd(UUID accountId) {
        SubscriptionRecord s = current(accountId);
        emitter.send(StripeEventFactory.event("customer.subscription.trial_will_end",
                StripeEventFactory.subscription(snapshot(s)), null, Instant.now()));
    }

    /** A renewal charge is declined; Stripe will retry (Smart Retries) and the subscription goes past_due. */
    public void renewalFailed(UUID accountId) {
        SubscriptionRecord s = current(accountId);
        Instant now = Instant.now();
        var sub = snapshot(s);
        DunningCase open = dunning.open(accountId).orElse(null);
        String invoiceId = open != null ? open.stripeInvoiceId() : StripeEventFactory.id("in");
        int attempt = open != null ? open.failedAttempts() + 1 : 1;
        Instant next = attempt >= 4 ? null : now.plus(Duration.ofDays(attempt == 1 ? 3 : attempt == 2 ? 5 : 7));
        emitter.send(StripeEventFactory.event("invoice.payment_failed", StripeEventFactory.invoice(
                new StripeEventFactory.Inv(invoiceId, s.stripeCustomerId(), s.stripeSubscriptionId(), accountId, "open",
                        sub.unitAmount(), 0, attempt, next, "subscription_cycle", now)), null, now));
        if (!"past_due".equals(s.status())) {
            var pastDue = sub.withStatus("past_due").withPeriod(sub.periodEnd(), plusInterval(sub.periodEnd(), sub.interval()));
            emitter.send(StripeEventFactory.event("customer.subscription.updated",
                    StripeEventFactory.subscription(pastDue), JsonNodeFactory.instance.objectNode()
                            .put("status", s.status()), now));
        }
    }

    /** A retry succeeds (customer updated the card): invoice.paid, back to active. */
    public void retrySucceeded(UUID accountId) {
        SubscriptionRecord s = current(accountId);
        DunningCase open = dunning.open(accountId)
                .orElseThrow(() -> new ConflictException("No failed payment to retry"));
        Instant now = Instant.now();
        var sub = snapshot(s);
        emitter.send(StripeEventFactory.event("invoice.paid", StripeEventFactory.invoice(new StripeEventFactory.Inv(
                open.stripeInvoiceId(), s.stripeCustomerId(), s.stripeSubscriptionId(), accountId, "paid",
                sub.unitAmount(), sub.unitAmount(), open.failedAttempts() + 1, null, "subscription_cycle", now)), null, now));
        emitter.send(StripeEventFactory.event("customer.subscription.updated",
                StripeEventFactory.subscription(sub.withStatus("active")),
                JsonNodeFactory.instance.objectNode().put("status", s.status()), now));
    }

    /** All retries failed and Stripe cancels the subscription (Billing settings: "cancel the subscription"). */
    public void finalFailure(UUID accountId) {
        SubscriptionRecord s = current(accountId);
        Instant now = Instant.now();
        emitter.send(StripeEventFactory.event("customer.subscription.deleted",
                StripeEventFactory.subscription(snapshot(s).withStatus("canceled").withCanceledAt(now)), null, now));
    }

    /** Stripe retries deliveries; the inbox must treat the same event id as a no-op. */
    public void redeliverLast(UUID accountId) {
        StoredEvent last = events.recent(1, null, null, accountId).stream().findFirst()
                .orElseThrow(() -> new ConflictException("No events for this account yet"));
        emitter.deliver(last.payload());
    }
}
