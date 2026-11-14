package dev.paykit.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Proration calculator")
class ProrationCalculatorTest {

    static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    static final Instant END = Instant.parse("2026-12-01T00:00:00Z");

    @Test
    @DisplayName("upgrading halfway through charges half the price difference")
    void halfway() {
        assertThat(ProrationCalculator.prorate(2900, 7900, START, END, Instant.parse("2026-11-16T00:00:00Z")))
                .isEqualTo(2500);
    }

    @Test
    @DisplayName("downgrading produces a credit (negative amount)")
    void downgradeCredit() {
        assertThat(ProrationCalculator.prorate(7900, 900, START, END, Instant.parse("2026-11-16T00:00:00Z")))
                .isEqualTo(-3500);
    }

    @Test
    @DisplayName("nothing is prorated at or after period end; an empty period is rejected")
    void edges() {
        assertThat(ProrationCalculator.prorate(900, 2900, START, END, END)).isZero();
        assertThat(ProrationCalculator.prorate(900, 2900, START, END, START)).isEqualTo(2000);
        assertThatThrownBy(() -> ProrationCalculator.prorate(1, 2, END, START, START))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
