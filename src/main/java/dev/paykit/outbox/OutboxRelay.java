package dev.paykit.outbox;

import java.time.Clock;
import java.time.Instant;

import dev.paykit.config.PayKitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers outbox messages at least once. Failures are retried with exponential backoff and parked as DEAD
 * after {@code paykit.outbox.max-attempts}; several app instances can run the relay (SKIP LOCKED).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxService outbox;
    private final NotificationSender sender;
    private final PayKitProperties.Outbox cfg;
    private final Clock clock;

    public OutboxRelay(OutboxService outbox, NotificationSender sender, PayKitProperties props, Clock clock) {
        this.outbox = outbox;
        this.sender = sender;
        this.cfg = props.outbox();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${paykit.outbox.poll-interval}", initialDelayString = "5s")
    public void scheduled() {
        relay();
    }

    /** @return number of messages delivered in this batch */
    @Transactional
    public int relay() {
        Instant now = clock.instant();
        int sent = 0;
        for (OutboxMessage m : outbox.claimDue(cfg.batchSize(), now)) {
            try {
                sender.send(m);
                outbox.markSent(m.id());
                sent++;
            } catch (Exception e) {
                int attempts = m.attempts() + 1;
                boolean dead = attempts >= cfg.maxAttempts();
                Instant next = now.plus(Backoff.delay(attempts, cfg.baseDelay(), cfg.maxDelay()));
                outbox.markFailed(m.id(), attempts, String.valueOf(e.getMessage()), next, dead);
                log.warn("Outbox message {} ({}) failed, attempt {}{}: {}", m.id(), m.topic(), attempts,
                        dead ? " -> DEAD" : ", next at " + next, e.getMessage());
            }
        }
        return sent;
    }
}
