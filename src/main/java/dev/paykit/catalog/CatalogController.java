package dev.paykit.catalog;

import java.util.List;
import java.util.Map;

import com.stripe.exception.StripeException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Catalog")
public class CatalogController {

    private final CatalogRepository catalog;
    private final CatalogService service;

    public CatalogController(CatalogRepository catalog, CatalogService service) {
        this.catalog = catalog;
        this.service = service;
    }

    public record PlanView(String code, String name, int tier, int trialDays, Map<String, Object> features,
                           List<PlanPrice> prices) {
    }

    @GetMapping("/api/plans")
    @Operation(summary = "Plans, prices and features (public)")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    public List<PlanView> plans() {
        List<PlanPrice> prices = catalog.prices();
        return catalog.plans().stream().map(p -> new PlanView(p.code(), p.name(), p.tier(), p.trialDays(), p.features(),
                prices.stream().filter(x -> x.planCode().equals(p.code())).toList())).toList();
    }

    @PostMapping("/api/admin/catalog/sync")
    @Operation(summary = "Create/match Stripe products and prices by lookup_key (idempotent)")
    public List<CatalogService.SyncedPrice> sync() throws StripeException {
        return service.sync();
    }
}
