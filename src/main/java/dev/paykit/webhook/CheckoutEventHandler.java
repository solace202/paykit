package dev.paykit.webhook;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import dev.paykit.account.Account;
import dev.paykit.account.AccountRepository;
import dev.paykit.billing.CheckoutRepository;

import org.springframework.stereotype.Component;

/** {@code checkout.session.completed|expired}: links the Stripe customer and closes the local checkout record. */
@Component
public class CheckoutEventHandler implements StripeEventHandler {

    private final AccountResolver resolver;
    private final AccountRepository accounts;
    private final CheckoutRepository checkouts;

    public CheckoutEventHandler(AccountResolver resolver, AccountRepository accounts, CheckoutRepository checkouts) {
        this.resolver = resolver;
        this.accounts = accounts;
        this.checkouts = checkouts;
    }

    @Override
    public Set<String> types() {
        return Set.of("checkout.session.completed", "checkout.session.expired");
    }

    @Override
    public EventOutcome handle(StripeEvent ev) {
        JsonNode session = ev.object();
        Optional<Account> found = resolver.resolve(session);
        if (found.isEmpty()) {
            return EventOutcome.ignored("checkout session without a PayKit account");
        }
        Account account = found.get();
        String customer = StripeEvent.text(session, "customer");
        if (account.stripeCustomerId() == null && customer != null) {
            accounts.setStripeCustomer(account.id(), customer);
        }
        String checkoutId = StripeEvent.text(session.path("metadata"), "checkout_id");
        boolean completed = ev.type().endsWith("completed");
        if (checkoutId != null) {
            checkouts.setStatus(UUID.fromString(checkoutId), completed ? "COMPLETED" : "EXPIRED");
        }
        return EventOutcome.processed(account.id(), completed
                ? "checkout completed, subscription " + StripeEvent.text(session, "subscription")
                : "checkout expired");
    }
}
