package dev.paykit.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import dev.paykit.billing.SubscriptionRecord;
import dev.paykit.dunning.DunningCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Entitlement policy")
class EntitlementPolicyTest {

    static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    static SubscriptionRecord sub(String status) {
        return new SubscriptionRecord(UUID.randomUUID(), UUID.randomUUID(), "sub_1", "si_1", "cus_1", "pro",
                "pro_monthly", status, null, NOW.plus(Duration.ofDays(20)), false, null, NOW, NOW);
    }

    static DunningCase openCase(Instant graceUntil) {
        return new DunningCase(UUID.randomUUID(), UUID.randomUUID(), "in_1", "OPEN", 1, NOW, NOW, null, graceUntil, null);
    }

    @Test
    @DisplayName("no subscription means Free; trialing and active get the plan")
    void basics() {
        assertThat(EntitlementPolicy.decide(Optional.empty(), Optional.empty(), NOW).planCode()).isEqualTo("free");
        assertThat(EntitlementPolicy.decide(Optional.of(sub("trialing")), Optional.empty(), NOW).planCode()).isEqualTo("pro");
        assertThat(EntitlementPolicy.decide(Optional.of(sub("active")), Optional.empty(), NOW).planCode()).isEqualTo("pro");
    }

    @Test
    @DisplayName("past_due keeps the plan only inside the grace period")
    void grace() {
        var inGrace = EntitlementPolicy.decide(Optional.of(sub("past_due")),
                Optional.of(openCase(NOW.plus(Duration.ofDays(2)))), NOW);
        var over = EntitlementPolicy.decide(Optional.of(sub("past_due")),
                Optional.of(openCase(NOW.minus(Duration.ofDays(1)))), NOW);
        assertThat(inGrace.planCode()).isEqualTo("pro");
        assertThat(over.planCode()).isEqualTo("free");
        assertThat(over.reason()).contains("grace period over");
    }

    @Test
    @DisplayName("canceled, unpaid, incomplete and paused subscriptions get Free")
    void inactive() {
        for (String s : new String[] {"canceled", "unpaid", "incomplete", "incomplete_expired", "paused"}) {
            assertThat(EntitlementPolicy.decide(Optional.of(sub(s)), Optional.empty(), NOW).planCode()).isEqualTo("free");
        }
    }
}
