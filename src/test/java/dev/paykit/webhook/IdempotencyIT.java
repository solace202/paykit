package dev.paykit.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.paykit.demo.StripeEventFactory;
import dev.paykit.demo.WebhookSigner;
import dev.paykit.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("Idempotent event processing")
class IdempotencyIT extends IntegrationTest {

    @Autowired
    WebhookIngestService ingest;

    @Test
    @DisplayName("the same event delivered twice is applied once; the second delivery is only counted")
    void duplicateDelivery() throws Exception {
        UUID acc = newAccount("dup");
        ObjectNode ev = subEvent("customer.subscription.created", sub(acc, "pro_monthly", "active"), Instant.now());

        JsonNode first = deliver(ev);
        JsonNode second = deliver(ev);

        assertThat(first.get("duplicate").asBoolean()).isFalse();
        assertThat(second.get("duplicate").asBoolean()).isTrue();
        assertThat(second.get("status").asText()).isEqualTo("PROCESSED");
        assertThat(jdbc.sql("select deliveries from stripe_events where id = ?").param(ev.get("id").asText())
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(outboxCount(acc, "subscription.started")).isEqualTo(1);
    }

    @Test
    @DisplayName("8 concurrent deliveries of one event: exactly one is processed, one welcome email queued")
    void concurrentDeliveries() throws Exception {
        UUID acc = newAccount("race");
        ObjectNode ev = subEvent("customer.subscription.created", sub(acc, "starter_monthly", "active"), Instant.now());
        String payload = json.writeValueAsString(ev);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<WebhookIngestService.Receipt>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> ingest.ingest(payload, WebhookSigner.header(payload, WEBHOOK_SECRET, Instant.now())));
        }
        long fresh = 0;
        for (Future<WebhookIngestService.Receipt> f : pool.invokeAll(calls)) {
            fresh += f.get().duplicate() ? 0 : 1;
        }
        pool.shutdown();
        assertThat(fresh).isEqualTo(1);
        assertThat(eventStatus(ev.get("id").asText())).isEqualTo("PROCESSED");
        assertThat(outboxCount(acc, "subscription.started")).isEqualTo(1);
    }

    @Test
    @DisplayName("an older event arriving after a newer one does not overwrite the subscription")
    void outOfOrder() throws Exception {
        UUID acc = newAccount("order");
        StripeEventFactory.Sub s = subscribed(acc, "pro_monthly", "active");
        Instant t = Instant.now().plusSeconds(60);
        deliver(subEvent("customer.subscription.updated", s.withStatus("active"), t));
        ObjectNode stale = subEvent("customer.subscription.updated", s.withStatus("past_due"), t.minusSeconds(30));

        deliver(stale);

        assertThat(account(acc).at("/subscription/status").asText()).isEqualTo("active");
        assertThat(jdbc.sql("select note from stripe_events where id = ?").param(stale.get("id").asText())
                .query(String.class).single()).startsWith("stale");
    }

    @Test
    @DisplayName("event types without a handler are stored as IGNORED, not failed")
    void unknownType() throws Exception {
        ObjectNode ev = StripeEventFactory.event("radar.early_fraud_warning.created",
                json.createObjectNode().put("id", "issfr_1"), null, Instant.now());
        assertThat(deliver(ev).get("status").asText()).isEqualTo("IGNORED");
    }

    @Test
    @DisplayName("events for customers PayKit doesn't know are ignored with a reason")
    void unknownCustomer() throws Exception {
        ObjectNode obj = StripeEventFactory.subscription(new StripeEventFactory.Sub("sub_x", "cus_nobody",
                UUID.randomUUID(), "si_x", "price_x", "pro_monthly", 2900, "month", "active", null, Instant.now(),
                Instant.now().plusSeconds(3600), false, null));
        ObjectNode ev = StripeEventFactory.event("customer.subscription.updated", obj, null, Instant.now());
        assertThat(deliver(ev).get("status").asText()).isEqualTo("IGNORED");
        assertThat(jdbc.sql("select note from stripe_events where id = ?").param(ev.get("id").asText())
                .query(String.class).single()).contains("no local account");
    }
}
