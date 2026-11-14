package dev.paykit.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import dev.paykit.catalog.Plan;
import dev.paykit.catalog.PlanPrice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Plan change rules")
class PlanChangeTest {

    static final Plan STARTER = new Plan("starter", "Starter", 1, 14, Map.of(), null);
    static final Plan PRO = new Plan("pro", "Pro", 2, 14, Map.of(), null);
    static final PlanPrice STARTER_M = new PlanPrice("starter_monthly", "starter", "month", 900, "usd", "p1");
    static final PlanPrice PRO_M = new PlanPrice("pro_monthly", "pro", "month", 2900, "usd", "p2");
    static final PlanPrice PRO_Y = new PlanPrice("pro_yearly", "pro", "year", 29000, "usd", "p3");

    @Test
    @DisplayName("higher tier is an upgrade, lower tier a downgrade, same price is SAME")
    void tiers() {
        assertThat(PlanChange.direction(STARTER, STARTER_M, PRO, PRO_M)).isEqualTo(PlanChange.Direction.UPGRADE);
        assertThat(PlanChange.direction(PRO, PRO_M, STARTER, STARTER_M)).isEqualTo(PlanChange.Direction.DOWNGRADE);
        assertThat(PlanChange.direction(PRO, PRO_M, PRO, PRO_M)).isEqualTo(PlanChange.Direction.SAME);
    }

    @Test
    @DisplayName("monthly to yearly on the same plan is an upgrade, yearly to monthly a downgrade")
    void intervals() {
        assertThat(PlanChange.direction(PRO, PRO_M, PRO, PRO_Y)).isEqualTo(PlanChange.Direction.UPGRADE);
        assertThat(PlanChange.direction(PRO, PRO_Y, PRO, PRO_M)).isEqualTo(PlanChange.Direction.DOWNGRADE);
    }

    @Test
    @DisplayName("proration: upgrade always_invoice, downgrade configurable, nothing during a trial")
    void proration() {
        assertThat(PlanChange.prorationBehavior(PlanChange.Direction.UPGRADE, "active", "create_prorations"))
                .isEqualTo("always_invoice");
        assertThat(PlanChange.prorationBehavior(PlanChange.Direction.DOWNGRADE, "active", "none")).isEqualTo("none");
        assertThat(PlanChange.prorationBehavior(PlanChange.Direction.UPGRADE, "trialing", "create_prorations"))
                .isEqualTo("none");
    }
}
