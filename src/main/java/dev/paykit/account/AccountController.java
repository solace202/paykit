package dev.paykit.account;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.stripe.exception.StripeException;
import dev.paykit.billing.CheckoutService;
import dev.paykit.billing.InvoiceRepository;
import dev.paykit.billing.SubscriptionRecord;
import dev.paykit.billing.SubscriptionRepository;
import dev.paykit.billing.SubscriptionService;
import dev.paykit.dunning.DunningCase;
import dev.paykit.dunning.DunningRepository;
import dev.paykit.entitlement.EntitlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Accounts & subscriptions")
public class AccountController {

    private final AccountService accountService;
    private final AccountRepository accounts;
    private final SubscriptionRepository subscriptions;
    private final InvoiceRepository invoices;
    private final DunningRepository dunning;
    private final EntitlementService entitlements;
    private final CheckoutService checkout;
    private final SubscriptionService subscriptionService;

    public AccountController(AccountService accountService, AccountRepository accounts,
                             SubscriptionRepository subscriptions, InvoiceRepository invoices, DunningRepository dunning,
                             EntitlementService entitlements, CheckoutService checkout,
                             SubscriptionService subscriptionService) {
        this.accountService = accountService;
        this.accounts = accounts;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.dunning = dunning;
        this.entitlements = entitlements;
        this.checkout = checkout;
        this.subscriptionService = subscriptionService;
    }

    public record CreateAccount(@NotBlank @Email String email, String name) {
    }

    public record PlanRequest(@NotBlank String plan, @NotBlank @Pattern(regexp = "month|year") String interval) {
    }

    public record AccountView(Account account, SubscriptionRecord subscription,
                              EntitlementService.EntitlementView entitlements, List<DunningCase> dunning,
                              List<InvoiceRepository.InvoiceRow> invoices) {
    }

    public record UrlResponse(String url) {
    }

    @PostMapping("/api/accounts")
    @Operation(summary = "Create an account on the Free plan and its Stripe Customer")
    public ResponseEntity<Account> create(@Valid @RequestBody CreateAccount req) throws StripeException {
        Account a = accountService.create(req.email(), req.name());
        return ResponseEntity.created(URI.create("/api/accounts/" + a.id())).body(a);
    }

    @GetMapping("/api/accounts/{id}")
    @Operation(summary = "Account with subscription, entitlements, dunning cases and invoices")
    public AccountView get(@PathVariable UUID id) {
        Account a = accounts.get(id);
        return new AccountView(a, subscriptions.findByAccount(id).orElse(null), entitlements.get(id),
                dunning.forAccount(id), invoices.forAccount(id));
    }

    @PostMapping("/api/accounts/{id}/checkout")
    @Operation(summary = "Start a Stripe Checkout Session for a plan (trial applied once per account)")
    public CheckoutService.CheckoutView checkout(@PathVariable UUID id, @Valid @RequestBody PlanRequest req)
            throws StripeException {
        return checkout.start(id, req.plan(), req.interval());
    }

    @PostMapping("/api/accounts/{id}/portal")
    @Operation(summary = "Stripe Customer Portal link (update card, invoices, cancel)")
    public UrlResponse portal(@PathVariable UUID id) throws StripeException {
        return new UrlResponse(subscriptionService.portalUrl(id, null));
    }

    @PostMapping("/api/accounts/{id}/subscription/preview")
    @Operation(summary = "Preview a plan change: direction, proration behavior and amount from Stripe")
    public SubscriptionService.ChangePlan preview(@PathVariable UUID id, @Valid @RequestBody PlanRequest req)
            throws StripeException {
        return subscriptionService.preview(id, req.plan(), req.interval());
    }

    @PostMapping("/api/accounts/{id}/subscription/change")
    @Operation(summary = "Upgrade (prorated, invoiced now) or downgrade (credit on next invoice)")
    public ResponseEntity<SubscriptionService.ChangePlan> change(@PathVariable UUID id,
                                                                 @Valid @RequestBody PlanRequest req)
            throws StripeException {
        return ResponseEntity.accepted().body(subscriptionService.change(id, req.plan(), req.interval()));
    }

    @PostMapping("/api/accounts/{id}/subscription/cancel")
    @Operation(summary = "Cancel at the end of the current period")
    public ResponseEntity<SubscriptionRecord> cancel(@PathVariable UUID id) throws StripeException {
        return ResponseEntity.accepted().body(subscriptionService.cancelAtPeriodEnd(id));
    }

    @PostMapping("/api/accounts/{id}/subscription/resume")
    @Operation(summary = "Undo a scheduled cancellation")
    public ResponseEntity<SubscriptionRecord> resume(@PathVariable UUID id) throws StripeException {
        return ResponseEntity.accepted().body(subscriptionService.resume(id));
    }

    @GetMapping("/api/accounts/{id}/entitlements")
    @Operation(summary = "What the account may use right now")
    public EntitlementService.EntitlementView entitlements(@PathVariable UUID id) {
        accounts.get(id);
        return entitlements.get(id);
    }

    @GetMapping("/api/accounts/{id}/entitlements/{feature}")
    @Operation(summary = "Check one feature, e.g. sso or projects")
    public EntitlementService.FeatureCheck feature(@PathVariable UUID id, @PathVariable String feature) {
        accounts.get(id);
        return entitlements.check(id, feature);
    }
}
