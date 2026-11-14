package dev.paykit.dunning;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import dev.paykit.config.PayKitProperties;

import org.springframework.stereotype.Service;

/**
 * Failed-payment recovery. Stripe's Smart Retries retry the card; PayKit tracks the case, keeps the customer's
 * access during a grace period, notifies them (via the outbox), and closes the case as RECOVERED when an invoice
 * is paid or LOST when Stripe gives up and cancels / marks the subscription unpaid.
 */
@Service
public class DunningService {

    private final DunningRepository repo;
    private final PayKitProperties props;
    private final Clock clock;

    public DunningService(DunningRepository repo, PayKitProperties props, Clock clock) {
        this.repo = repo;
        this.props = props;
        this.clock = clock;
    }

    /** Returns the open case after recording this failed attempt. */
    public DunningCase recordFailure(UUID accountId, String invoiceId, int attempt, Instant failedAt,
                                     Instant nextAttempt) {
        Optional<DunningCase> open = repo.open(accountId);
        if (open.isPresent()) {
            repo.updateAttempt(open.get().id(), attempt, failedAt, nextAttempt);
        } else {
            repo.insert(new DunningCase(UUID.randomUUID(), accountId, invoiceId, "OPEN", Math.max(attempt, 1), failedAt,
                    failedAt, nextAttempt, failedAt.plus(props.billing().gracePeriod()), null));
        }
        return repo.open(accountId).orElseThrow();
    }

    /** @return true if an open case was closed */
    public boolean recover(UUID accountId) {
        return repo.close(accountId, "RECOVERED") > 0;
    }

    public boolean lose(UUID accountId) {
        return repo.close(accountId, "LOST") > 0;
    }

    public Optional<DunningCase> open(UUID accountId) {
        return repo.open(accountId);
    }

    public Instant now() {
        return clock.instant();
    }
}
