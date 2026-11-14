package dev.paykit.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import dev.paykit.demo.StripeEventFactory;
import dev.paykit.demo.WebhookSigner;
import dev.paykit.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;

@DisplayName("Webhook signature verification")
class WebhookSignatureIT extends IntegrationTest {

    private String payload() throws Exception {
        return json.writeValueAsString(StripeEventFactory.event("customer.created",
                json.createObjectNode().put("id", "cus_x").put("object", "customer"), null, Instant.now()));
    }

    private long stored(String payload) throws Exception {
        String id = json.readTree(payload).get("id").asText();
        return jdbc.sql("select count(*) from stripe_events where id = ?").param(id).query(Long.class).single();
    }

    @Test
    @DisplayName("a correctly signed event is accepted and stored")
    void validSignature() throws Exception {
        String p = payload();
        deliver(p, WebhookSigner.header(p, WEBHOOK_SECRET, Instant.now()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.status").value("IGNORED"));
        assertThat(stored(p)).isEqualTo(1);
    }

    @Test
    @DisplayName("missing Stripe-Signature header is rejected with 400")
    void missingHeader() throws Exception {
        String p = payload();
        mvc.perform(post("/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(p))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://paykit.dev/problems/invalid-signature"));
        assertThat(stored(p)).isZero();
    }

    @Test
    @DisplayName("signature made with another secret is rejected")
    void wrongSecret() throws Exception {
        String p = payload();
        deliver(p, WebhookSigner.header(p, "whsec_attacker", Instant.now())).andExpect(status().isBadRequest());
        assertThat(stored(p)).isZero();
    }

    @Test
    @DisplayName("a payload changed after signing is rejected")
    void tampered() throws Exception {
        String p = payload();
        String sig = WebhookSigner.header(p, WEBHOOK_SECRET, Instant.now());
        deliver(p.replace("cus_x", "cus_y"), sig).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a replayed signature older than the 5-minute tolerance is rejected")
    void tooOld() throws Exception {
        String p = payload();
        deliver(p, WebhookSigner.header(p, WEBHOOK_SECRET, Instant.now().minus(Duration.ofMinutes(10))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("tolerance")));
        assertThat(stored(p)).isZero();
    }
}
