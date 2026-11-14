package dev.paykit.webhook;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import dev.paykit.account.Account;
import dev.paykit.account.AccountRepository;

import org.springframework.stereotype.Component;

/**
 * Finds the local account a Stripe object belongs to: {@code metadata.account_id} (set on every object PayKit
 * creates), then {@code client_reference_id} (Checkout), then the Stripe customer id.
 */
@Component
public class AccountResolver {

    private final AccountRepository accounts;

    public AccountResolver(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Optional<Account> resolve(JsonNode obj, JsonNode... extraMetadata) {
        Optional<Account> byMeta = byId(obj.path("metadata").path("account_id"));
        for (JsonNode meta : extraMetadata) {
            if (byMeta.isEmpty()) {
                byMeta = byId(meta.path("account_id"));
            }
        }
        if (byMeta.isPresent()) {
            return byMeta;
        }
        Optional<Account> byRef = byId(obj.path("client_reference_id"));
        if (byRef.isPresent()) {
            return byRef;
        }
        String customer = StripeEvent.text(obj, "customer");
        return customer == null ? Optional.empty() : accounts.findByStripeCustomer(customer);
    }

    private Optional<Account> byId(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        try {
            return accounts.find(UUID.fromString(node.asText()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
