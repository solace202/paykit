package dev.paykit.catalog;

import java.util.ArrayList;
import java.util.List;

import com.stripe.exception.StripeException;
import dev.paykit.config.PayKitProperties;
import dev.paykit.stripe.StripeGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Pushes the local catalog to Stripe. Safe to run any number of times: prices are matched by their
 * {@code lookup_key} and creates carry an Idempotency-Key, so nothing is duplicated.
 */
@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private final CatalogRepository catalog;
    private final StripeGateway stripe;
    private final PayKitProperties props;

    public CatalogService(CatalogRepository catalog, StripeGateway stripe, PayKitProperties props) {
        this.catalog = catalog;
        this.stripe = stripe;
        this.props = props;
    }

    public record SyncedPrice(String lookupKey, String stripePriceId, boolean created) {
    }

    public List<SyncedPrice> sync() throws StripeException {
        List<SyncedPrice> result = new ArrayList<>();
        for (Plan plan : catalog.plans()) {
            if (plan.isFree()) {
                continue;
            }
            String productId = plan.stripeProductId();
            if (productId == null) {
                productId = stripe.ensureProduct(plan);
                catalog.setStripeProductId(plan.code(), productId);
            }
            for (PlanPrice price : catalog.prices()) {
                if (!price.planCode().equals(plan.code())) {
                    continue;
                }
                StripeGateway.PriceRef ref = stripe.ensurePrice(productId, price);
                catalog.setStripePriceId(price.lookupKey(), ref.id());
                result.add(new SyncedPrice(price.lookupKey(), ref.id(), ref.created()));
            }
        }
        return result;
    }

    @EventListener(ApplicationReadyEvent.class)
    void syncOnStartup() {
        if (!props.stripe().syncCatalogOnStartup()) {
            return;
        }
        try {
            log.info("Catalog synced to Stripe: {}", sync());
        } catch (Exception e) {
            log.warn("Catalog sync on startup failed ({}); run POST /api/admin/catalog/sync later", e.getMessage());
        }
    }
}
