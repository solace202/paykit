package dev.paykit.stripe;

import java.util.UUID;

import com.stripe.exception.StripeException;
import dev.paykit.billing.SubscriptionRecord;
import dev.paykit.catalog.Plan;
import dev.paykit.catalog.PlanPrice;

/**
 * Every call PayKit makes to the Stripe API. The production implementation is {@link SdkStripeGateway}
 * (official stripe-java). In demo mode a decorator also plays Stripe's webhook side.
 */
public interface StripeGateway {

    record PriceRef(String id, boolean created) {
    }

    record CheckoutRequest(UUID checkoutId, UUID accountId, String customerId, PlanPrice price, int trialDays,
                           String successUrl, String cancelUrl) {
    }

    record CheckoutRef(String sessionId, String url) {
    }

    /** What the customer would be charged now / on the next invoice for a plan change. */
    record ChangePreview(long amountDue, long total, String currency, String prorationBehavior) {
    }

    String ensureProduct(Plan plan) throws StripeException;

    PriceRef ensurePrice(String productId, PlanPrice price) throws StripeException;

    String createCustomer(UUID accountId, String email, String name) throws StripeException;

    CheckoutRef createCheckoutSession(CheckoutRequest request) throws StripeException;

    String createPortalSession(String customerId, String returnUrl) throws StripeException;

    ChangePreview previewPriceChange(SubscriptionRecord sub, PlanPrice from, PlanPrice to, String prorationBehavior)
            throws StripeException;

    void changePrice(SubscriptionRecord sub, PlanPrice to, String prorationBehavior, String idempotencyKey)
            throws StripeException;

    void setCancelAtPeriodEnd(SubscriptionRecord sub, boolean cancel) throws StripeException;
}
