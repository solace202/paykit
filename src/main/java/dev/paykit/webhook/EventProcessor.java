package dev.paykit.webhook;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.paykit.config.PayKitProperties;
import dev.paykit.outbox.Backoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies one stored event exactly once. The event row is locked, the handler runs, and the row is marked
 * PROCESSED in the same transaction, so a concurrent redelivery waits and then sees it is done. If the handler
 * throws, its changes roll back and the event is marked FAILED with an exponential-backoff retry time.
 */
@Service
public class EventProcessor {

    private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

    private final StripeEventRepository events;
    private final Map<String, StripeEventHandler> handlers = new HashMap<>();
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final PayKitProperties.Events cfg;
    private final Clock clock;

    public EventProcessor(StripeEventRepository events, List<StripeEventHandler> handlerBeans,
                          PlatformTransactionManager txManager, ObjectMapper json, PayKitProperties props, Clock clock) {
        this.events = events;
        handlerBeans.forEach(h -> h.types().forEach(t -> handlers.put(t, h)));
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
        this.cfg = props.events();
        this.clock = clock;
    }

    /** @return the event's status afterwards */
    public String process(String eventId) {
        try {
            return tx.execute(status -> apply(eventId));
        } catch (RuntimeException e) {
            return tx.execute(status -> fail(eventId, e));
        }
    }

    private String apply(String eventId) {
        StoredEvent stored = events.lock(eventId).orElseThrow();
        if (!"RECEIVED".equals(stored.status()) && !"FAILED".equals(stored.status())) {
            return stored.status();                  // already done by a concurrent delivery
        }
        StripeEvent event;
        try {
            event = StripeEvent.of(json.readTree(stored.payload()));
        } catch (Exception e) {
            throw new IllegalStateException("unreadable payload", e);
        }
        StripeEventHandler handler = handlers.get(event.type());
        EventOutcome outcome = handler == null
                ? EventOutcome.ignored("no handler for " + event.type())
                : handler.handle(event);
        events.markDone(eventId, outcome, stored.attempts() + 1);
        return outcome.status();
    }

    private String fail(String eventId, RuntimeException e) {
        StoredEvent stored = events.find(eventId).orElseThrow();
        int attempts = stored.attempts() + 1;
        boolean dead = attempts >= cfg.maxAttempts();
        Instant next = clock.instant().plus(Backoff.delay(attempts, cfg.retryBaseDelay(), cfg.retryMaxDelay()));
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        events.markFailed(eventId, attempts, message, next, dead);
        log.warn("Stripe event {} ({}) failed, attempt {}{}: {}", eventId, stored.type(), attempts,
                dead ? " -> DEAD" : ", retry at " + next, message);
        return dead ? "DEAD" : "FAILED";
    }
}
