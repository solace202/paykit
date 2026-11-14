package dev.paykit.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The offline demo end to end over real HTTP: the app calls stripe-mock through the SDK and plays Stripe's
 * webhook side by POSTing signed events to its own /webhooks/stripe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "paykit.demo.enabled=true",
        "paykit.scheduling.enabled=false",
        "paykit.demo.webhook-target=http://localhost:${local.server.port}/webhooks/stripe",
        "paykit.public-url=http://localhost:${local.server.port}"})
@DisplayName("Offline demo flow over HTTP (stripe-mock + self-signed webhooks)")
class DemoFlowIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
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

    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper json;

    private JsonNode api(String path, Object body) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth("admin", "change-me");
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = body == null
                ? http.exchange(path, org.springframework.http.HttpMethod.GET, new HttpEntity<>(h), String.class)
                : http.postForEntity(path, new HttpEntity<>(body, h), String.class);
        return json.readTree(r.getBody());
    }

    private void form(String path) {
        http.postForEntity(path, null, String.class);
    }

    @Test
    @DisplayName("trial checkout, end of trial, failed renewal, duplicate delivery, recovery")
    void lifecycle() throws Exception {
        JsonNode acc = api("/api/accounts", Map.of("email", "demo@example.com", "name", "Demo"));
        String id = acc.get("id").asText();
        JsonNode co = api("/api/accounts/" + id + "/checkout", Map.of("plan", "pro", "interval", "month"));
        assertThat(co.get("url").asText()).contains("/demo/checkout/");

        form("/demo/checkout/" + co.get("checkoutId").asText() + "/pay");
        assertThat(api("/api/accounts/" + id, null).at("/subscription/status").asText()).isEqualTo("trialing");

        form("/demo/accounts/" + id + "/end-trial");
        form("/demo/accounts/" + id + "/renewal-failed");
        JsonNode a = api("/api/accounts/" + id, null);
        assertThat(a.at("/subscription/status").asText()).isEqualTo("past_due");
        assertThat(a.at("/entitlements/plan").asText()).isEqualTo("pro");

        form("/demo/accounts/" + id + "/redeliver");
        JsonNode events = api("/api/admin/events?accountId=" + id, null);
        assertThat(events.findValues("deliveries").stream().mapToInt(JsonNode::asInt).max().orElse(0)).isEqualTo(2);

        form("/demo/accounts/" + id + "/retry-succeeded");
        a = api("/api/accounts/" + id, null);
        assertThat(a.at("/subscription/status").asText()).isEqualTo("active");
        assertThat(a.at("/dunning/0/status").asText()).isEqualTo("RECOVERED");
        assertThat(events.findValuesAsText("status")).doesNotContain("FAILED", "DEAD");
    }
}
