package dev.paykit.billing;

import dev.paykit.catalog.Plan;
import dev.paykit.catalog.PlanPrice;

/** Decides whether a price change is an upgrade or a downgrade, and how Stripe should prorate it. */
public final class PlanChange {

    public enum Direction { UPGRADE, DOWNGRADE, SAME }

    private PlanChange() {
    }

    public static Direction direction(Plan fromPlan, PlanPrice from, Plan toPlan, PlanPrice to) {
        if (from.lookupKey().equals(to.lookupKey())) {
            return Direction.SAME;
        }
        if (fromPlan.tier() != toPlan.tier()) {
            return toPlan.tier() > fromPlan.tier() ? Direction.UPGRADE : Direction.DOWNGRADE;
        }
        // Same plan, other interval: monthly -> yearly is a commitment upgrade.
        return "year".equals(to.interval()) ? Direction.UPGRADE : Direction.DOWNGRADE;
    }

    /**
     * Upgrades are charged immediately for the rest of the period ({@code always_invoice}); downgrades credit
     * the unused time on the next invoice (configurable); nothing is prorated while trialing.
     */
    public static String prorationBehavior(Direction direction, String status, String downgradeBehavior) {
        if ("trialing".equals(status)) {
            return "none";
        }
        return direction == Direction.UPGRADE ? "always_invoice" : downgradeBehavior;
    }
}
