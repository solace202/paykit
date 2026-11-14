package dev.paykit.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.paykit.demo.StripeEventFactory;
import dev.paykit.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

@DisplayName("Failed event processing: retry with backoff, dead letter, replay")
class EventRetryIT extends IntegrationTest {

    @Autowired
    EventRetryJob retryJob;

    /** A subscription on a price PayKit doesn't know yet makes the handler throw. */
    private ObjectNode unknownPriceEvent(UUID acc, String lookupKey) {
        var s = new StripeEventFactory.Sub(StripeEventFactory.id("sub"), customerOf(acc), acc, "si_1",
                "price_" + lookupKey, lookupKey, 4900, "month", "active", null, Instant.now(),
                Instant.now().plus(Duration.ofDays(30)), false, null);
        return subEvent("customer.subscription.created", s, Instant.now());
    }

    private void addPlan(String code) {
        jdbc.sql("insert into plans (code, name, tier, features) values (?, 'Business', 4, cast(? as jsonb))")
                .params(code, "{\"projects\": 100, \"seats\": 20}").update();
        jdbc.sql("insert into plan_prices (lookup_key, plan_code, billing_interval, unit_amount) values (?, ?, 'month', 4900)")
                .params(code, code).update();
    }

    @Test
    @DisplayName("a failing handler is rolled back, answered 200, and retried later until it succeeds")
    void retriedUntilFixed() throws Exception {
        UUID acc = newAccount("retry");
        String key = "biz_" + UUID.randomUUID().toString().substring(0, 6);
        ObjectNode ev = unknownPriceEvent(acc, key);
        assertThat(deliver(ev).get("status").asText()).isEqualTo("FAILED");
        assertThat(account(acc).has("subscription")).isFalse();

        // Operator adds the missing price; the retry job picks the event up once its backoff has passed.
        addPlan(key);
        retryJob.retryDue();
        assertThat(eventStatus(ev.get("id").asText())).isEqualTo("FAILED");   // backoff not elapsed
        clock.advance(Duration.ofSeconds(11));
        retryJob.retryDue();
        assertThat(eventStatus(ev.get("id").asText())).isEqualTo("PROCESSED");
        assertThat(account(acc).at("/subscription/planCode").asText()).isEqualTo(key);
    }

    @Test
    @DisplayName("after max attempts the event is DEAD; an admin can replay it")
    void deadAndReplay() throws Exception {
        UUID acc = newAccount("dead");
        String key = "biz_" + UUID.randomUUID().toString().substring(0, 6);
        ObjectNode ev = unknownPriceEvent(acc, key);
        String id = ev.get("id").asText();
        deliver(ev);
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofHours(2));
            retryJob.retryDue();
        }
        assertThat(eventStatus(id)).isEqualTo("DEAD");
        assertThat(jdbc.sql("select attempts from stripe_events where id = ?").param(id).query(Integer.class).single())
                .isEqualTo(3);

        addPlan(key);
        call(HttpMethod.POST, "/api/admin/events/" + id + "/replay", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSED"));
    }
}
