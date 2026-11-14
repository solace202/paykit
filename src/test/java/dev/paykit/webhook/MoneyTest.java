package dev.paykit.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Money formatting")
class MoneyTest {

    @Test
    @DisplayName("minor units respect the currency's decimals, including zero-decimal currencies")
    void format() {
        assertThat(Money.format(2900, "usd")).isEqualTo("$29.00");
        assertThat(Money.format(-350, "eur")).isEqualTo("-€3.50");
        assertThat(Money.format(1200, "jpy")).isEqualTo("JPY 1200");
    }
}
