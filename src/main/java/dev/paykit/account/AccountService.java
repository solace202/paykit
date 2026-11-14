package dev.paykit.account;

import java.util.UUID;

import com.stripe.exception.StripeException;
import dev.paykit.entitlement.EntitlementService;
import dev.paykit.stripe.StripeGateway;
import dev.paykit.web.ConflictException;

import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final StripeGateway stripe;
    private final EntitlementService entitlements;

    public AccountService(AccountRepository accounts, StripeGateway stripe, EntitlementService entitlements) {
        this.accounts = accounts;
        this.stripe = stripe;
        this.entitlements = entitlements;
    }

    /** Creates the local account on the Free plan, then its Stripe Customer (idempotent per account id). */
    public Account create(String email, String name) throws StripeException {
        if (accounts.findByEmail(email).isPresent()) {
            throw new ConflictException("An account with email " + email + " already exists");
        }
        UUID id = UUID.randomUUID();
        accounts.insert(id, email, name);
        entitlements.recompute(id);
        ensureCustomer(accounts.get(id));
        return accounts.get(id);
    }

    public Account findOrCreate(String email, String name) throws StripeException {
        var existing = accounts.findByEmail(email);
        return existing.isPresent() ? existing.get() : create(email, name);
    }

    public String ensureCustomer(Account account) throws StripeException {
        if (account.stripeCustomerId() != null) {
            return account.stripeCustomerId();
        }
        String customerId = stripe.createCustomer(account.id(), account.email(), account.name());
        accounts.setStripeCustomer(account.id(), customerId);
        return customerId;
    }
}
