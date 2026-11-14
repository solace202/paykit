package dev.paykit.billing;

import java.util.UUID;

import com.stripe.exception.StripeException;
import dev.paykit.account.Account;
import dev.paykit.account.AccountRepository;
import dev.paykit.account.AccountService;
import dev.paykit.catalog.CatalogRepository;
import dev.paykit.catalog.Plan;
import dev.paykit.catalog.PlanPrice;
import dev.paykit.config.PayKitProperties;
import dev.paykit.stripe.StripeGateway;
import dev.paykit.web.ConflictException;
import dev.paykit.web.NotFoundException;

import org.springframework.stereotype.Service;

@Service
public class CheckoutService {

    private final AccountRepository accounts;
    private final AccountService accountService;
    private final CatalogRepository catalog;
    private final SubscriptionRepository subscriptions;
    private final CheckoutRepository checkouts;
    private final StripeGateway stripe;
    private final PayKitProperties props;

    public CheckoutService(AccountRepository accounts, AccountService accountService, CatalogRepository catalog,
                           SubscriptionRepository subscriptions, CheckoutRepository checkouts, StripeGateway stripe,
                           PayKitProperties props) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.catalog = catalog;
        this.subscriptions = subscriptions;
        this.checkouts = checkouts;
        this.stripe = stripe;
        this.props = props;
    }

    public record CheckoutView(UUID checkoutId, String stripeSessionId, String url, String priceLookupKey,
                               long unitAmount, String currency, int trialDays) {
    }

    public CheckoutView start(UUID accountId, String planCode, String interval) throws StripeException {
        Account account = accounts.get(accountId);
        Plan plan = catalog.plan(planCode);
        PlanPrice price = catalog.price(planCode, interval)
                .orElseThrow(() -> new NotFoundException("No " + interval + "ly price for plan " + planCode));
        if (price.stripePriceId() == null) {
            throw new ConflictException("Catalog not synced to Stripe yet: POST /api/admin/catalog/sync");
        }
        subscriptions.findByAccount(accountId).filter(SubscriptionRecord::isChangeable).ifPresent(s -> {
            throw new ConflictException("Account already has a " + s.status() + " subscription; change the plan instead");
        });
        // Free trial only once per account, whatever the plan.
        int trialDays = !account.trialUsed() && plan.trialDays() > 0 ? plan.trialDays() : 0;
        String customerId = accountService.ensureCustomer(account);
        UUID checkoutId = UUID.randomUUID();
        StripeGateway.CheckoutRef ref = stripe.createCheckoutSession(new StripeGateway.CheckoutRequest(checkoutId,
                accountId, customerId, price, trialDays,
                props.url("/account/" + accountId + "?checkout=success&session_id={CHECKOUT_SESSION_ID}"),
                props.url("/pricing?canceled=1")));
        checkouts.insert(checkoutId, accountId, price.lookupKey(), ref.sessionId(), ref.url(), trialDays);
        return new CheckoutView(checkoutId, ref.sessionId(), ref.url(), price.lookupKey(), price.unitAmount(),
                price.currency(), trialDays);
    }
}
