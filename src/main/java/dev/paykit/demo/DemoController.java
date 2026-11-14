package dev.paykit.demo;

import java.util.UUID;

import dev.paykit.account.AccountRepository;
import dev.paykit.billing.CheckoutRepository;
import dev.paykit.catalog.CatalogRepository;
import dev.paykit.web.NotFoundException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Demo only: a stand-in for Stripe's hosted Checkout page plus buttons that trigger Stripe-side events. */
@Controller
@ConditionalOnProperty(name = "paykit.demo.enabled", havingValue = "true")
public class DemoController {

    private final DemoStripeSimulator simulator;
    private final CheckoutRepository checkouts;
    private final AccountRepository accounts;
    private final CatalogRepository catalog;

    public DemoController(DemoStripeSimulator simulator, CheckoutRepository checkouts, AccountRepository accounts,
                          CatalogRepository catalog) {
        this.simulator = simulator;
        this.checkouts = checkouts;
        this.accounts = accounts;
        this.catalog = catalog;
    }

    @GetMapping("/demo/checkout/{id}")
    public String checkout(@PathVariable UUID id, Model model) {
        var session = checkouts.find(id).orElseThrow(() -> new NotFoundException("Checkout not found"));
        var price = catalog.price(session.priceLookupKey()).orElseThrow();
        model.addAttribute("session", session);
        model.addAttribute("account", accounts.get(session.accountId()));
        model.addAttribute("price", price);
        model.addAttribute("plan", catalog.plan(price.planCode()));
        return "demo-checkout";
    }

    @PostMapping("/demo/checkout/{id}/pay")
    public String pay(@PathVariable UUID id) {
        UUID accountId = simulator.completeCheckout(id);
        return "redirect:/account/" + accountId + "?checkout=success";
    }

    @PostMapping("/demo/accounts/{id}/{action}")
    public String action(@PathVariable UUID id, @PathVariable String action, RedirectAttributes flash) {
        String msg = switch (action) {
            case "end-trial" -> {
                simulator.endTrial(id);
                yield "Stripe: trial ended, first invoice paid";
            }
            case "trial-will-end" -> {
                simulator.trialWillEnd(id);
                yield "Stripe: customer.subscription.trial_will_end sent";
            }
            case "renewal-failed" -> {
                simulator.renewalFailed(id);
                yield "Stripe: renewal payment declined (invoice.payment_failed)";
            }
            case "retry-succeeded" -> {
                simulator.retrySucceeded(id);
                yield "Stripe: retry succeeded (invoice.paid)";
            }
            case "final-failure" -> {
                simulator.finalFailure(id);
                yield "Stripe: retries exhausted, subscription canceled";
            }
            case "redeliver" -> {
                simulator.redeliverLast(id);
                yield "Stripe: last event delivered again (same event id)";
            }
            default -> throw new NotFoundException("Unknown demo action " + action);
        };
        flash.addFlashAttribute("msg", msg);
        return "redirect:/account/" + id;
    }
}
