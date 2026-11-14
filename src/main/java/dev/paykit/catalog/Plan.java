package dev.paykit.catalog;

import java.util.Map;

/** A plan and its features. A feature value of {@code -1} means unlimited. */
public record Plan(String code, String name, int tier, int trialDays, Map<String, Object> features,
                   String stripeProductId) {

    public static final String FREE = "free";

    public boolean isFree() {
        return FREE.equals(code);
    }
}
