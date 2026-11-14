package dev.paykit.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Exponential backoff")
class BackoffTest {

    @Test
    @DisplayName("doubles each attempt and is capped at the maximum")
    void doubles() {
        Duration base = Duration.ofSeconds(10);
        Duration max = Duration.ofMinutes(5);
        assertThat(Backoff.delay(1, base, max)).isEqualTo(Duration.ofSeconds(10));
        assertThat(Backoff.delay(2, base, max)).isEqualTo(Duration.ofSeconds(20));
        assertThat(Backoff.delay(4, base, max)).isEqualTo(Duration.ofSeconds(80));
        assertThat(Backoff.delay(10, base, max)).isEqualTo(max);
        assertThat(Backoff.delay(500, base, max)).isEqualTo(max);
    }
}
