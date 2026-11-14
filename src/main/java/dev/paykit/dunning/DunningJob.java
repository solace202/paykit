package dev.paykit.dunning;

import java.util.Map;

import dev.paykit.entitlement.EntitlementService;
import dev.paykit.outbox.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** When a grace period runs out while Stripe is still retrying, paid features are suspended. */
@Component
public class DunningJob {

    private static final Logger log = LoggerFactory.getLogger(DunningJob.class);

    private final DunningRepository repo;
    private final DunningService dunning;
    private final EntitlementService entitlements;
    private final OutboxService outbox;

    public DunningJob(DunningRepository repo, DunningService dunning, EntitlementService entitlements,
                      OutboxService outbox) {
        this.repo = repo;
        this.dunning = dunning;
        this.entitlements = entitlements;
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${paykit.billing.grace-check-interval:5m}", initialDelayString = "30s")
    @Transactional
    public int suspendExpiredGracePeriods() {
        int n = 0;
        for (DunningCase c : repo.openPastGrace(dunning.now())) {
            var view = entitlements.recompute(c.accountId());
            outbox.enqueue("access.suspended", c.accountId(), "suspended-" + c.id(), Map.of(
                    "subject", "Your paid features are paused until the payment is fixed",
                    "invoice", c.stripeInvoiceId(), "failedAttempts", c.failedAttempts(), "plan", view.plan()));
            n++;
        }
        if (n > 0) {
            log.info("Grace period over for {} account(s); entitlements reduced", n);
        }
        return n;
    }
}
