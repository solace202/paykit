package dev.paykit.web;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.paykit.account.Account;
import dev.paykit.account.AccountRepository;
import dev.paykit.account.AccountService;
import dev.paykit.billing.CheckoutService;
import dev.paykit.billing.InvoiceRepository;
import dev.paykit.billing.SubscriptionRepository;
import dev.paykit.billing.SubscriptionService;
import dev.paykit.catalog.CatalogController;
import dev.paykit.config.PayKitProperties;
import dev.paykit.dunning.DunningRepository;
import dev.paykit.entitlement.EntitlementService;
import dev.paykit.outbox.OutboxService;
import dev.paykit.webhook.StoredEvent;
import dev.paykit.webhook.StripeEventRepository;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Example storefront (pricing page and a customer billing page) and the operator's webhook log.
 * In a real product the customer pages live in your own frontend behind its login and call the JSON API.
 */
@Controller
public class PageController {

    private final CatalogController catalog;
    private final AccountService accountService;
    private final AccountRepository accounts;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionService subscriptionService;
    private final CheckoutService checkout;
    private final InvoiceRepository invoices;
    private final DunningRepository dunning;
    private final EntitlementService entitlements;
    private final StripeEventRepository events;
    private final OutboxService outbox;
    private final PayKitProperties props;

    public PageController(CatalogController catalog, AccountService accountService, AccountRepository accounts,
                          SubscriptionRepository subscriptions, SubscriptionService subscriptionService,
                          CheckoutService checkout, InvoiceRepository invoices, DunningRepository dunning,
                          EntitlementService entitlements, StripeEventRepository events, OutboxService outbox,
                          PayKitProperties props) {
        this.catalog = catalog;
        this.accountService = accountService;
        this.accounts = accounts;
        this.subscriptions = subscriptions;
        this.subscriptionService = subscriptionService;
        this.checkout = checkout;
        this.invoices = invoices;
        this.dunning = dunning;
        this.entitlements = entitlements;
        this.events = events;
        this.outbox = outbox;
        this.props = props;
    }

    @GetMapping({"/", "/pricing"})
    public String pricing(Model model, @RequestParam(defaultValue = "month") String interval) {
        String iv = "year".equals(interval) ? "year" : "month";
        var plans = catalog.plans();
        Map<String, Object> priceFor = new LinkedHashMap<>();
        plans.forEach(p -> p.prices().stream().filter(x -> x.interval().equals(iv)).findFirst()
                .ifPresent(x -> priceFor.put(p.code(), x)));
        model.addAttribute("plans", plans);
        model.addAttribute("priceFor", priceFor);
        model.addAttribute("interval", iv);
        model.addAttribute("demo", props.demo().enabled());
        return "pricing";
    }

    @PostMapping("/checkout")
    public String startCheckout(@RequestParam String email, @RequestParam String plan, @RequestParam String interval,
                                RedirectAttributes flash) throws Exception {
        Account account = accountService.findOrCreate(email.trim(), null);
        try {
            return "redirect:" + checkout.start(account.id(), plan, interval).url();
        } catch (ConflictException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/account/" + account.id();
        }
    }

    @GetMapping("/account/{id}")
    public String account(@PathVariable UUID id, Model model) {
        model.addAttribute("account", accounts.get(id));
        model.addAttribute("sub", subscriptions.findByAccount(id).orElse(null));
        model.addAttribute("ent", entitlements.get(id));
        model.addAttribute("invoices", invoices.forAccount(id));
        model.addAttribute("dunning", dunning.open(id).orElse(null));
        model.addAttribute("events", events.recent(10, null, null, id));
        model.addAttribute("outbox", outbox.recent(6, id));
        model.addAttribute("plans", catalog.plans());
        model.addAttribute("demo", props.demo().enabled());
        return "account";
    }

    @PostMapping("/account/{id}/change")
    public String change(@PathVariable UUID id, @RequestParam String price, RedirectAttributes flash) throws Exception {
        String[] parts = price.split(":");
        return run(id, flash, () -> {
            var r = subscriptionService.change(id, parts[0], parts[1]);
            return r.direction() + " to " + r.to() + " sent to Stripe (" + r.prorationBehavior() + ")";
        });
    }

    @PostMapping("/account/{id}/cancel")
    public String cancel(@PathVariable UUID id, RedirectAttributes flash) {
        return run(id, flash, () -> {
            subscriptionService.cancelAtPeriodEnd(id);
            return "Cancellation at period end requested";
        });
    }

    @PostMapping("/account/{id}/resume")
    public String resume(@PathVariable UUID id, RedirectAttributes flash) {
        return run(id, flash, () -> {
            subscriptionService.resume(id);
            return "Subscription resumed";
        });
    }

    @PostMapping("/account/{id}/portal")
    public String portal(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            return "redirect:" + subscriptionService.portalUrl(id, "/account/" + id);
        } catch (Exception e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/account/" + id;
        }
    }

    @GetMapping("/admin/events")
    public String eventLog(Model model, @RequestParam(required = false) String status) {
        List<StoredEvent> list = events.recent(25, status, null, null);
        Map<String, Long> counts = new LinkedHashMap<>();
        Arrays.asList("PROCESSED", "IGNORED", "FAILED", "DEAD").forEach(s -> counts.put(s, 0L));
        events.recent(500, null, null, null).forEach(e -> counts.merge(e.status(), 1L, Long::sum));
        long redeliveries = events.recent(500, null, null, null).stream().mapToLong(e -> e.deliveries() - 1).sum();
        model.addAttribute("events", list);
        model.addAttribute("counts", counts);
        model.addAttribute("redeliveries", redeliveries);
        model.addAttribute("outbox", outbox.recent(8, null));
        model.addAttribute("accounts", accounts.recent(50).stream()
                .collect(LinkedHashMap<UUID, String>::new, (m, a) -> m.put(a.id(), a.email()), Map::putAll));
        return "events";
    }

    interface Action {
        String run() throws Exception;
    }

    private String run(UUID id, RedirectAttributes flash, Action action) {
        try {
            flash.addFlashAttribute("msg", action.run());
        } catch (Exception e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/account/" + id;
    }
}
