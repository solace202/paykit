package dev.paykit.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.paykit.catalog.CatalogRepository;
import dev.paykit.catalog.PlanPrice;
import dev.paykit.demo.StripeEventFactory;
import dev.paykit.demo.WebhookSigner;
import org.junit.jupiter.api.BeforeEach;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Real PostgreSQL 16 and the official stripe-mock (Stripe's OpenAPI-driven mock server) via Testcontainers,
 * started once per JVM. Webhooks are signed with the configured secret exactly like Stripe signs them.
 * Background jobs are off; tests drive them with a movable clock.
 */
@SpringBootTest(properties = {
        "paykit.scheduling.enabled=false",
        "paykit.stripe.webhook-secret=" + IntegrationTest.WEBHOOK_SECRET,
        "paykit.outbox.max-attempts=3",
        "paykit.events.max-attempts=3"})
@AutoConfigureMockMvc
@Import(IntegrationTest.TestBeans.class)
public abstract class IntegrationTest {

    public static final String WEBHOOK_SECRET = "whsec_test_secret_for_integration_tests";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("paykit").withUsername("paykit").withPassword("paykit");
    static final GenericContainer<?> STRIPE_MOCK = new GenericContainer<>("stripe/stripe-mock:latest")
            .withExposedPorts(12111).waitingFor(Wait.forListeningPort());

    static {
        POSTGRES.start();
        STRIPE_MOCK.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("paykit.stripe.api-base", () -> "http://" + STRIPE_MOCK.getHost() + ":" + STRIPE_MOCK.getMappedPort(12111));
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class TestBeans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        RecordingSender recordingSender() {
            return new RecordingSender();
        }
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected CatalogRepository catalog;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected RecordingSender sender;

    @BeforeEach
    void resetDoubles() {
        clock.reset();
        sender.reset();
    }

    // ------------------------------------------------------------------ HTTP helpers

    protected ResultActions call(HttpMethod method, String url, Object body) throws Exception {
        MockHttpServletRequestBuilder req = request(method, url).accept(MediaType.APPLICATION_JSON)
                .with(httpBasic("admin", "change-me"));
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        }
        return mvc.perform(req);
    }

    protected JsonNode read(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsByteArray());
    }

    protected UUID newAccount(String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        JsonNode a = read(call(HttpMethod.POST, "/api/accounts", Map.of("email", email, "name", prefix)));
        return UUID.fromString(a.get("id").asText());
    }

    protected String customerOf(UUID accountId) {
        return jdbc.sql("select stripe_customer_id from accounts where id = ?").param(accountId).query(String.class).single();
    }

    // ------------------------------------------------------------------ webhook helpers

    protected ResultActions deliver(String payload, String signature) throws Exception {
        return mvc.perform(post("/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", signature).content(payload));
    }

    protected JsonNode deliver(ObjectNode event) throws Exception {
        String payload = json.writeValueAsString(event);
        return read(deliver(payload, WebhookSigner.header(payload, WEBHOOK_SECRET, Instant.now())));
    }

    protected StripeEventFactory.Sub sub(UUID accountId, String lookupKey, String status) {
        PlanPrice p = catalog.price(lookupKey).orElseThrow();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant trialEnd = "trialing".equals(status) ? now.plus(Duration.ofDays(14)) : null;
        return new StripeEventFactory.Sub(StripeEventFactory.id("sub"), customerOf(accountId), accountId,
                StripeEventFactory.id("si"), p.stripePriceId(), p.lookupKey(), p.unitAmount(), p.interval(), status,
                trialEnd, now, trialEnd != null ? trialEnd : now.plus(Duration.ofDays(30)), false, null);
    }

    protected ObjectNode subEvent(String type, StripeEventFactory.Sub s, Instant created) {
        return StripeEventFactory.event(type, StripeEventFactory.subscription(s), null, created);
    }

    protected ObjectNode invoiceEvent(String type, StripeEventFactory.Sub s, String invoiceId, int attempt, long paid) {
        Instant now = Instant.now();
        return StripeEventFactory.event(type, StripeEventFactory.invoice(new StripeEventFactory.Inv(invoiceId,
                s.customer(), s.id(), s.accountId(), paid > 0 ? "paid" : "open", s.unitAmount(), paid, attempt,
                paid > 0 ? null : now.plus(Duration.ofDays(3)), "subscription_cycle", now)), null, now);
    }

    /** An account with an active (or trialing) subscription confirmed by webhook. */
    protected StripeEventFactory.Sub subscribed(UUID accountId, String lookupKey, String status) throws Exception {
        StripeEventFactory.Sub s = sub(accountId, lookupKey, status);
        deliver(subEvent("customer.subscription.created", s, Instant.now()));
        return s;
    }

    protected JsonNode account(UUID id) throws Exception {
        return read(call(HttpMethod.GET, "/api/accounts/" + id, null));
    }

    protected int outboxCount(UUID accountId, String topic) {
        return jdbc.sql("select count(*) from outbox_messages where account_id = ? and topic = ?")
                .params(accountId, topic).query(Integer.class).single();
    }

    protected String eventStatus(String eventId) {
        return jdbc.sql("select status from stripe_events where id = ?").param(eventId).query(String.class).single();
    }
}
