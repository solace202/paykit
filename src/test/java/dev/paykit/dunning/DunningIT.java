package dev.paykit.dunning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import dev.paykit.demo.StripeEventFactory;
import dev.paykit.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("Dunning: failed payments, grace period, recovery")
class DunningIT extends IntegrationTest {

    @Autowired
    DunningJob job;

    private StripeEventFactory.Sub failing(UUID acc) throws Exception {
        StripeEventFactory.Sub s = subscribed(acc, "pro_monthly", "active");
        deliver(invoiceEvent("invoice.payment_failed", s, "in_" + acc.toString().substring(0, 8), 1, 0));
        deliver(subEvent("customer.subscription.updated", s.withStatus("past_due"), Instant.now().plusSeconds(1)));
        return s;
    }

    @Test
    @DisplayName("a failed renewal opens a dunning case, keeps access in the grace period and emails the customer")
    void failureOpensCase() throws Exception {
        UUID acc = newAccount("fail");
        failing(acc);
        JsonNode a = account(acc);
        assertThat(a.at("/subscription/status").asText()).isEqualTo("past_due");
        assertThat(a.at("/dunning/0/status").asText()).isEqualTo("OPEN");
        assertThat(a.at("/entitlements/plan").asText()).isEqualTo("pro");
        assertThat(a.at("/entitlements/reason").asText()).contains("grace period");
        assertThat(outboxCount(acc, "payment.failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("further retries update the same case and send one email per attempt")
    void retriesUpdateCase() throws Exception {
        UUID acc = newAccount("retry");
        StripeEventFactory.Sub s = failing(acc);
        deliver(invoiceEvent("invoice.payment_failed", s, "in_" + acc.toString().substring(0, 8), 2, 0));
        JsonNode a = account(acc);
        assertThat(a.get("dunning")).hasSize(1);
        assertThat(a.at("/dunning/0/failedAttempts").asInt()).isEqualTo(2);
        assertThat(outboxCount(acc, "payment.failed")).isEqualTo(2);
    }

    @Test
    @DisplayName("when the grace period runs out, paid features are suspended")
    void graceExpires() throws Exception {
        UUID acc = newAccount("grace");
        failing(acc);
        clock.advance(Duration.ofDays(8));
        assertThat(job.suspendExpiredGracePeriods()).isGreaterThanOrEqualTo(1);
        JsonNode a = account(acc);
        assertThat(a.at("/entitlements/plan").asText()).isEqualTo("free");
        assertThat(outboxCount(acc, "access.suspended")).isEqualTo(1);
    }

    @Test
    @DisplayName("a successful retry closes the case as RECOVERED and restores the plan")
    void recovery() throws Exception {
        UUID acc = newAccount("recover");
        StripeEventFactory.Sub s = failing(acc);
        deliver(invoiceEvent("invoice.paid", s, "in_" + acc.toString().substring(0, 8), 2, s.unitAmount()));
        deliver(subEvent("customer.subscription.updated", s.withStatus("active"), Instant.now().plusSeconds(5)));
        JsonNode a = account(acc);
        assertThat(a.at("/dunning/0/status").asText()).isEqualTo("RECOVERED");
        assertThat(a.at("/subscription/status").asText()).isEqualTo("active");
        assertThat(a.at("/entitlements/plan").asText()).isEqualTo("pro");
        assertThat(outboxCount(acc, "payment.recovered")).isEqualTo(1);
        assertThat(outboxCount(acc, "invoice.receipt")).isEqualTo(1);
    }

    @Test
    @DisplayName("Stripe giving up (subscription deleted) closes the case as LOST and drops to Free")
    void lost() throws Exception {
        UUID acc = newAccount("lost");
        StripeEventFactory.Sub s = failing(acc);
        deliver(subEvent("customer.subscription.deleted", s.withStatus("canceled"), Instant.now().plusSeconds(5)));
        JsonNode a = account(acc);
        assertThat(a.at("/dunning/0/status").asText()).isEqualTo("LOST");
        assertThat(a.at("/entitlements/plan").asText()).isEqualTo("free");
    }
}
