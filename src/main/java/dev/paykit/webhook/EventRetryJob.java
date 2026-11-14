package dev.paykit.webhook;

import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-runs FAILED events whose backoff has elapsed. */
@Component
public class EventRetryJob {

    private final StripeEventRepository events;
    private final EventProcessor processor;
    private final Clock clock;

    public EventRetryJob(StripeEventRepository events, EventProcessor processor, Clock clock) {
        this.events = events;
        this.processor = processor;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${paykit.events.retry-poll-interval}", initialDelayString = "10s")
    public void scheduled() {
        retryDue();
    }

    public int retryDue() {
        var due = events.dueForRetry(clock.instant(), 50);
        due.forEach(processor::process);
        return due.size();
    }
}
