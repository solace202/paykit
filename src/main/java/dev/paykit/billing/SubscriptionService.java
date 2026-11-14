package dev.paykit.billing;

import java.util.UUID;

import com.stripe.exception.StripeException;
import dev.paykit.account.Account;
import dev.paykit.account.AccountRepository;
import dev.paykit.catalog.CatalogRepository;
import dev.paykit.catalog.PlanPrice;
import dev.paykit.config.PayKitProperties;
import dev.paykit.stripe.StripeGateway;
import dev.paykit.web.ConflictException;
import dev.paykit.web.NotFoundException;

import org.springframework.stereotype.Service;

/**
 * Plan changes and cancellation. These call Stripe and return; the local subscription only changes when
 * Stripe confirms with a {@code customer.subscription.updated} webhook, so Stripe stays the source of truth.
 */
@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptions;
    private final AccountRepository accounts;
    private final CatalogRepository catalog;
    private final StripeGateway stripe;
    private final PayKitProperties props;

    public SubscriptionService(SubscriptionRepository subscriptions, AccountRepository accounts,
                               CatalogRepository catalog, StripeGateway stripe, PayKitProperties props) {
        this.subscriptions = subscriptions;
        this.accounts = accounts;
        this.catalog = catalog;
        this.stripe = stripe;
        this.props = props;
    }

    public record ChangePlan(PlanChange.Direction direction, String from, String to, String prorationBehavior,
                             StripeGateway.ChangePreview preview, String state) {
    }

    public SubscriptionRecord current(UUID accountId) {
        accounts.get(accountId);
        return subscriptions.findByAccount(accountId)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " has no subscription"));
    }

    private SubscriptionRecord changeable(UUID accountId) {
        SubscriptionRecord sub = current(accountId);
        if (!sub.isChangeable()) {
            throw new ConflictException("Subscription is " + sub.status() + "; start a new checkout instead");
        }
        return sub;
    }

    public ChangePlan preview(UUID accountId, String planCode, String interval) throws StripeException {
        return plan(accountId, planCode, interval, false);
    }

    public ChangePlan change(UUID accountId, String planCode, String interval) throws StripeException {
        return plan(accountId, planCode, interval, true);
    }

    private ChangePlan plan(UUID accountId, String planCode, String interval, boolean apply) throws StripeException {
        SubscriptionRecord sub = changeable(accountId);
        PlanPrice from = catalog.price(sub.priceLookupKey())
                .orElseThrow(() -> new ConflictException("Current price " + sub.priceLookupKey() + " is not in the catalog"));
        PlanPrice to = catalog.price(planCode, interval)
                .orElseThrow(() -> new NotFoundException("No " + interval + "ly price for plan " + planCode));
        if (to.stripePriceId() == null) {
            throw new ConflictException("Catalog not synced to Stripe yet: POST /api/admin/catalog/sync");
        }
        PlanChange.Direction direction = PlanChange.direction(catalog.plan(from.planCode()), from,
                catalog.plan(to.planCode()), to);
        if (direction == PlanChange.Direction.SAME) {
            throw new ConflictException("Already on " + to.lookupKey());
        }
        if (apply && sub.cancelAtPeriodEnd()) {
            throw new ConflictException("Subscription is set to cancel at period end; resume it first");
        }
        String behavior = PlanChange.prorationBehavior(direction, sub.status(), props.billing().downgradeProration());
        StripeGateway.ChangePreview preview = stripe.previewPriceChange(sub, from, to, behavior);
        if (!apply) {
            return new ChangePlan(direction, from.lookupKey(), to.lookupKey(), behavior, preview, "PREVIEW");
        }
        // Same request retried => same key => Stripe applies it once.
        String key = "change-" + sub.stripeSubscriptionId() + "-" + to.lookupKey() + "-"
                + sub.lastEventCreated().getEpochSecond();
        stripe.changePrice(sub, to, behavior, key);
        return new ChangePlan(direction, from.lookupKey(), to.lookupKey(), behavior, preview, "SENT_TO_STRIPE");
    }

    public SubscriptionRecord cancelAtPeriodEnd(UUID accountId) throws StripeException {
        SubscriptionRecord sub = changeable(accountId);
        if (!sub.cancelAtPeriodEnd()) {
            stripe.setCancelAtPeriodEnd(sub, true);
        }
        return current(accountId);
    }

    public SubscriptionRecord resume(UUID accountId) throws StripeException {
        SubscriptionRecord sub = changeable(accountId);
        if (sub.cancelAtPeriodEnd()) {
            stripe.setCancelAtPeriodEnd(sub, false);
        }
        return current(accountId);
    }

    public String portalUrl(UUID accountId, String returnPath) throws StripeException {
        Account account = accounts.get(accountId);
        if (account.stripeCustomerId() == null) {
            throw new ConflictException("Account has no Stripe customer yet");
        }
        return stripe.createPortalSession(account.stripeCustomerId(),
                props.url(returnPath != null ? returnPath : "/account/" + accountId));
    }
}
