package dev.paykit.catalog;

public record PlanPrice(String lookupKey, String planCode, String interval, long unitAmount, String currency,
                        String stripePriceId) {
}
