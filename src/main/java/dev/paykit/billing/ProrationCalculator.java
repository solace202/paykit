package dev.paykit.billing;

import java.time.Duration;
import java.time.Instant;

/**
 * Stripe-style time-based proration: credit for the unused time on the old price, charge for the remaining
 * time on the new price, each rounded to the minor unit. Used by the offline demo to produce realistic
 * proration invoices (with real Stripe the amounts come from the API).
 */
public final class ProrationCalculator {

    private ProrationCalculator() {
    }

    public static long prorate(long oldAmount, long newAmount, Instant periodStart, Instant periodEnd, Instant at) {
        if (!periodEnd.isAfter(periodStart)) {
            throw new IllegalArgumentException("period end must be after period start");
        }
        if (!at.isBefore(periodEnd)) {
            return 0;
        }
        Instant from = at.isBefore(periodStart) ? periodStart : at;
        double fraction = (double) Duration.between(from, periodEnd).getSeconds()
                / Duration.between(periodStart, periodEnd).getSeconds();
        long credit = Math.round(oldAmount * fraction);
        long charge = Math.round(newAmount * fraction);
        return charge - credit;
    }
}
