package dev.paykit.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import dev.paykit.outbox.NotificationSender;
import dev.paykit.outbox.OutboxMessage;

/** Records deliveries; can be told to fail the next N sends. */
public class RecordingSender implements NotificationSender {

    public final List<OutboxMessage> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger failNext = new AtomicInteger();
    private volatile boolean failAlways;

    private volatile String onlyKey;

    /** Fail the next {@code n} sends of the message with this dedup key. */
    public void failNext(String dedupKey, int n) {
        onlyKey = dedupKey;
        failNext.set(n);
    }

    public void failAlways(boolean v) {
        failAlways = v;
    }

    public void reset() {
        sent.clear();
        failNext.set(0);
        failAlways = false;
        onlyKey = null;
    }

    @Override
    public void send(OutboxMessage message) {
        boolean targeted = onlyKey == null || onlyKey.equals(message.dedupKey());
        if (failAlways || (targeted && failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0)) {
            throw new IllegalStateException("mail server unavailable");
        }
        sent.add(message);
    }
}
