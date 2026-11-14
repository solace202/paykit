package dev.paykit.outbox;

import java.time.Duration;

/** Exponential backoff: base, 2×base, 4×base, … capped at max. */
public final class Backoff {

    private Backoff() {
    }

    public static Duration delay(int attempt, Duration base, Duration max) {
        if (attempt < 1) {
            return base;
        }
        int shift = Math.min(attempt - 1, 30);
        long millis = base.toMillis() * (1L << shift);
        return millis <= 0 || millis > max.toMillis() ? max : Duration.ofMillis(millis);
    }
}
