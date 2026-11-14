package dev.paykit.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import dev.paykit.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("Transactional outbox relay")
class OutboxRelayIT extends IntegrationTest {

    @Autowired
    OutboxService outbox;
    @Autowired
    OutboxRelay relay;

    @BeforeEach
    void drain() {
        relay.relay();       // deliver leftovers from other tests
        sender.reset();
    }

    private Map<String, Object> row(String key) {
        return jdbc.sql("select status, attempts, next_attempt_at from outbox_messages where dedup_key = ?").param(key)
                .query().singleRow();
    }

    @Test
    @DisplayName("queued messages are delivered once and marked SENT")
    void delivers() {
        String key = "t-" + UUID.randomUUID();
        assertThat(outbox.enqueue("test.topic", null, key, Map.of("subject", "hello"))).isTrue();
        assertThat(outbox.enqueue("test.topic", null, key, Map.of("subject", "hello"))).isFalse();
        relay.relay();
        relay.relay();
        assertThat(sender.sent).extracting(OutboxMessage::dedupKey).containsOnlyOnce(key);
        assertThat(row(key).get("status")).isEqualTo("SENT");
    }

    @Test
    @DisplayName("a failed send is retried after an exponential backoff, not immediately")
    void backoff() {
        String key = "t-" + UUID.randomUUID();
        outbox.enqueue("test.topic", null, key, Map.of("subject", "retry me"));
        sender.failNext(key, 1);
        relay.relay();
        assertThat(row(key).get("status")).isEqualTo("PENDING");
        assertThat(row(key).get("attempts")).isEqualTo(1);

        relay.relay();                         // backoff not elapsed yet
        assertThat(sender.sent).extracting(OutboxMessage::dedupKey).doesNotContain(key);

        clock.advance(Duration.ofSeconds(6));  // base delay is 5s
        relay.relay();
        assertThat(row(key).get("status")).isEqualTo("SENT");
    }

    @Test
    @DisplayName("after max attempts the message is parked as DEAD and can be requeued")
    void dead() {
        String key = "t-" + UUID.randomUUID();
        outbox.enqueue("test.topic", null, key, Map.of("subject", "never"));
        sender.failAlways(true);
        for (int i = 0; i < 3; i++) {
            relay.relay();
            clock.advance(Duration.ofMinutes(10));
        }
        assertThat(row(key).get("status")).isEqualTo("DEAD");

        long id = jdbc.sql("select id from outbox_messages where dedup_key = ?").param(key).query(Long.class).single();
        sender.failAlways(false);
        assertThat(outbox.retryDead(id)).isEqualTo(1);
        relay.relay();
        assertThat(row(key).get("status")).isEqualTo("SENT");
    }
}
