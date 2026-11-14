package dev.paykit.demo;

import java.util.UUID;

import com.stripe.exception.StripeException;
import dev.paykit.account.AccountRepository;
import dev.paykit.billing.ProrationCalculator;
import dev.paykit.billing.SubscriptionRecord;
import dev.paykit.catalog.Plan;
import dev.paykit.catalog.PlanPrice;
import dev.paykit.config.PayKitProperties;
import dev.paykit.stripe.SdkStripeGateway;
import dev.paykit.stripe.StripeGateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Demo only. Every call still goes through the real SDK to stripe-mock, which validates the request against
 * Stripe's OpenAPI spec. stripe-mock is stateless and returns fixed fixture ids, so this decorator substitutes
 * unique ids, a local Checkout page and Stripe-style proration amounts, and emits the webhooks Stripe would send.
 */
@Component
@Primary
@ConditionalOnProperty(name = "paykit.demo.enabled", havingValue = "true")
public class SimulatingStripeGateway implements StripeGateway {

    private final SdkStripeGateway sdk;
    private final DemoStripeSimulator simulator;
    private final AccountRepository accounts;
    private final PayKitProperties props;

    public SimulatingStripeGateway(SdkStripeGateway sdk, DemoStripeSimulator simulator, AccountRepository accounts,
                                   PayKitProperties props) {
        this.sdk = sdk;
        this.simulator = simulator;
        this.accounts = accounts;
        this.props = props;
    }

    @Override
    public String ensureProduct(Plan plan) throws StripeException {
        sdk.ensureProduct(plan);
        return "prod_paykit_" + plan.code();
    }

    @Override
    public PriceRef ensurePrice(String productId, PlanPrice price) throws StripeException {
        PriceRef ref = sdk.ensurePrice(productId, price);
        return new PriceRef("price_demo_" + price.lookupKey(), ref.created());
    }

    @Override
    public String createCustomer(UUID accountId, String email, String name) throws StripeException {
        sdk.createCustomer(accountId, email, name);
        return StripeEventFactory.id("cus");
    }

    @Override
    public CheckoutRef createCheckoutSession(CheckoutRequest request) throws StripeException {
        sdk.createCheckoutSession(request);
        return new CheckoutRef(StripeEventFactory.id("cs_test"), props.url("/demo/checkout/" + request.checkoutId()));
    }

    @Override
    public String createPortalSession(String customerId, String returnUrl) throws StripeException {
        sdk.createPortalSession(customerId, returnUrl);
        return accounts.findByStripeCustomer(customerId).map(a -> props.url("/account/" + a.id() + "?portal=demo"))
                .orElse(returnUrl);
    }

    @Override
    public ChangePreview previewPriceChange(SubscriptionRecord sub, PlanPrice from, PlanPrice to,
                                            String prorationBehavior) throws StripeException {
        sdk.previewPriceChange(sub, from, to, prorationBehavior);
        if ("none".equals(prorationBehavior)) {
            return new ChangePreview(0, 0, "usd", prorationBehavior);
        }
        var snap = simulator.snapshot(sub);
        long amount = ProrationCalculator.prorate(from.unitAmount(), to.unitAmount(), snap.periodStart(),
                snap.periodEnd(), java.time.Instant.now());
        return new ChangePreview("always_invoice".equals(prorationBehavior) ? Math.max(amount, 0) : 0, amount, "usd",
                prorationBehavior);
    }

    @Override
    public void changePrice(SubscriptionRecord sub, PlanPrice to, String prorationBehavior, String idempotencyKey)
            throws StripeException {
        sdk.changePrice(sub, to, prorationBehavior, idempotencyKey);
        simulator.priceChanged(sub, to, prorationBehavior);
    }

    @Override
    public void setCancelAtPeriodEnd(SubscriptionRecord sub, boolean cancel) throws StripeException {
        sdk.setCancelAtPeriodEnd(sub, cancel);
        simulator.cancelFlagChanged(sub, cancel);
    }
}
