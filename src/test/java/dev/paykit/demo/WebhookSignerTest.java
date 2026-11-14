package dev.paykit.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Stripe-Signature generation (checked by the official SDK)")
class WebhookSignerTest {

    @Test
    @DisplayName("headers produced for the demo and tests verify with stripe-java's Webhook.Signature")
    void verifiesWithSdk() throws Exception {
        String payload = "{\"id\":\"evt_1\",\"object\":\"event\"}";
        String header = WebhookSigner.header(payload, "whsec_abc", Instant.now());
        assertThat(header).matches("t=\\d+,v1=[0-9a-f]{64}");
        assertThat(Webhook.Signature.verifyHeader(payload, header, "whsec_abc", 300)).isTrue();
        assertThatThrownBy(() -> Webhook.Signature.verifyHeader(payload, header, "whsec_other", 300))
                .isInstanceOf(SignatureVerificationException.class);
    }

    @Test
    @DisplayName("event payloads carry the SDK's API version and current-API field layout")
    void eventShape() {
        var sub = StripeEventFactory.subscription(new StripeEventFactory.Sub("sub_1", "cus_1",
                java.util.UUID.randomUUID(), "si_1", "price_1", "pro_monthly", 2900, "month", "active", null,
                Instant.ofEpochSecond(100), Instant.ofEpochSecond(200), false, null));
        var ev = StripeEventFactory.event("customer.subscription.updated", sub, null, Instant.ofEpochSecond(150));
        assertThat(ev.get("api_version").asText()).isEqualTo(com.stripe.Stripe.API_VERSION);
        assertThat(ev.at("/data/object/items/data/0/current_period_end").asLong()).isEqualTo(200);
        assertThat(ev.at("/data/object/items/data/0/price/lookup_key").asText()).isEqualTo("pro_monthly");
    }
}
