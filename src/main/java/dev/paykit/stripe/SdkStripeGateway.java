package dev.paykit.stripe;

import java.time.Instant;
import java.util.UUID;

import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Invoice;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.InvoiceCreatePreviewParams;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.PriceListParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import dev.paykit.billing.SubscriptionRecord;
import dev.paykit.catalog.Plan;
import dev.paykit.catalog.PlanPrice;

import org.springframework.stereotype.Component;

/** Stripe API calls through the official SDK. Every create/update carries an Idempotency-Key. */
@Component
public class SdkStripeGateway implements StripeGateway {

    private final StripeClient stripe;

    public SdkStripeGateway(StripeClient stripe) {
        this.stripe = stripe;
    }

    private static RequestOptions idem(String key) {
        return RequestOptions.builder().setIdempotencyKey(key).build();
    }

    @Override
    public String ensureProduct(Plan plan) throws StripeException {
        String id = "paykit_" + plan.code();
        try {
            return stripe.v1().products().retrieve(id).getId();
        } catch (InvalidRequestException notFound) {
            Product created = stripe.v1().products().create(ProductCreateParams.builder()
                    .setId(id)
                    .setName("PayKit " + plan.name())
                    .putMetadata("paykit_plan", plan.code())
                    .build(), idem("product-" + plan.code()));
            return created.getId();
        }
    }

    @Override
    public PriceRef ensurePrice(String productId, PlanPrice price) throws StripeException {
        for (Price p : stripe.v1().prices().list(PriceListParams.builder()
                .addLookupKey(price.lookupKey()).setActive(true).build()).getData()) {
            if (price.lookupKey().equals(p.getLookupKey()) && Long.valueOf(price.unitAmount()).equals(p.getUnitAmount())
                    && price.currency().equalsIgnoreCase(p.getCurrency())) {
                return new PriceRef(p.getId(), false);
            }
        }
        // Not there, or the amount changed: create a new price and move the lookup_key to it.
        Price created = stripe.v1().prices().create(PriceCreateParams.builder()
                .setProduct(productId)
                .setCurrency(price.currency())
                .setUnitAmount(price.unitAmount())
                .setLookupKey(price.lookupKey())
                .setTransferLookupKey(true)
                .setRecurring(PriceCreateParams.Recurring.builder()
                        .setInterval("year".equals(price.interval())
                                ? PriceCreateParams.Recurring.Interval.YEAR
                                : PriceCreateParams.Recurring.Interval.MONTH)
                        .build())
                .putMetadata("paykit_plan", price.planCode())
                .build(), idem("price-" + price.lookupKey() + "-" + price.unitAmount()));
        return new PriceRef(created.getId(), true);
    }

    @Override
    public String createCustomer(UUID accountId, String email, String name) throws StripeException {
        CustomerCreateParams.Builder params = CustomerCreateParams.builder()
                .setEmail(email)
                .putMetadata("account_id", accountId.toString());
        if (name != null) {
            params.setName(name);
        }
        return stripe.v1().customers().create(params.build(), idem("customer-" + accountId)).getId();
    }

    @Override
    public CheckoutRef createCheckoutSession(CheckoutRequest r) throws StripeException {
        SessionCreateParams.SubscriptionData.Builder sub = SessionCreateParams.SubscriptionData.builder()
                .putMetadata("account_id", r.accountId().toString())
                .putMetadata("checkout_id", r.checkoutId().toString());
        if (r.trialDays() > 0) {
            sub.setTrialPeriodDays((long) r.trialDays());
        }
        Session session = stripe.v1().checkout().sessions().create(SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(r.customerId())
                .setClientReferenceId(r.accountId().toString())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(r.price().stripePriceId())
                        .setQuantity(1L)
                        .build())
                .setSubscriptionData(sub.build())
                .setAllowPromotionCodes(true)
                .setSuccessUrl(r.successUrl())
                .setCancelUrl(r.cancelUrl())
                .putMetadata("account_id", r.accountId().toString())
                .putMetadata("checkout_id", r.checkoutId().toString())
                .build(), idem("checkout-" + r.checkoutId()));
        return new CheckoutRef(session.getId(), session.getUrl());
    }

    @Override
    public String createPortalSession(String customerId, String returnUrl) throws StripeException {
        return stripe.v1().billingPortal().sessions().create(com.stripe.param.billingportal.SessionCreateParams.builder()
                .setCustomer(customerId)
                .setReturnUrl(returnUrl)
                .build()).getUrl();
    }

    @Override
    public ChangePreview previewPriceChange(SubscriptionRecord sub, PlanPrice from, PlanPrice to,
                                            String prorationBehavior) throws StripeException {
        Invoice preview = stripe.v1().invoices().createPreview(InvoiceCreatePreviewParams.builder()
                .setCustomer(sub.stripeCustomerId())
                .setSubscription(sub.stripeSubscriptionId())
                .setSubscriptionDetails(InvoiceCreatePreviewParams.SubscriptionDetails.builder()
                        .addItem(InvoiceCreatePreviewParams.SubscriptionDetails.Item.builder()
                                .setId(sub.stripeItemId())
                                .setPrice(to.stripePriceId())
                                .build())
                        .setProrationBehavior(InvoiceCreatePreviewParams.SubscriptionDetails.ProrationBehavior
                                .valueOf(prorationBehavior.toUpperCase()))
                        .setProrationDate(Instant.now().getEpochSecond())
                        .build())
                .build());
        return new ChangePreview(nz(preview.getAmountDue()), nz(preview.getTotal()), preview.getCurrency(),
                prorationBehavior);
    }

    @Override
    public void changePrice(SubscriptionRecord sub, PlanPrice to, String prorationBehavior, String idempotencyKey)
            throws StripeException {
        SubscriptionUpdateParams.Builder params = SubscriptionUpdateParams.builder()
                .addItem(SubscriptionUpdateParams.Item.builder()
                        .setId(sub.stripeItemId())
                        .setPrice(to.stripePriceId())
                        .build())
                .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.valueOf(prorationBehavior.toUpperCase()));
        if ("always_invoice".equals(prorationBehavior)) {
            // Upgrade only takes effect if the proration invoice is paid (no free upgrades on a failing card).
            params.setPaymentBehavior(SubscriptionUpdateParams.PaymentBehavior.PENDING_IF_INCOMPLETE);
        }
        stripe.v1().subscriptions().update(sub.stripeSubscriptionId(), params.build(), idem(idempotencyKey));
    }

    @Override
    public void setCancelAtPeriodEnd(SubscriptionRecord sub, boolean cancel) throws StripeException {
        stripe.v1().subscriptions().update(sub.stripeSubscriptionId(), SubscriptionUpdateParams.builder()
                .setCancelAtPeriodEnd(cancel).build());
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}
