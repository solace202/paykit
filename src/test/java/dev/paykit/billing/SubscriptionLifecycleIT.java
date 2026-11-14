package dev.paykit.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import dev.paykit.demo.StripeEventFactory;
import dev.paykit.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;

@DisplayName("Checkout, trials, plan changes and cancellation (Stripe API via stripe-mock)")
class SubscriptionLifecycleIT extends IntegrationTest {

    private JsonNode checkout(UUID acc, String plan, String interval) throws Exception {
        return read(call(HttpMethod.POST, "/api/accounts/" + acc + "/checkout",
                Map.of("plan", plan, "interval", interval)).andExpect(status().isOk()));
    }

    @Test
    @DisplayName("checkout session is created in Stripe with the 14-day trial on the first subscription")
    void checkoutWithTrial() throws Exception {
        UUID acc = newAccount("trial");
        JsonNode co = checkout(acc, "pro", "month");
        assertThat(co.get("stripeSessionId").asText()).startsWith("cs_");
        assertThat(co.get("url").asText()).startsWith("https://");
        assertThat(co.get("trialDays").asInt()).isEqualTo(14);
        assertThat(co.get("priceLookupKey").asText()).isEqualTo("pro_monthly");
    }

    @Test
    @DisplayName("subscription.created webhook activates the plan's entitlements and uses up the trial")
    void createdWebhook() throws Exception {
        UUID acc = newAccount("created");
        subscribed(acc, "pro_monthly", "trialing");
        JsonNode a = account(acc);
        assertThat(a.at("/subscription/status").asText()).isEqualTo("trialing");
        assertThat(a.at("/entitlements/plan").asText()).isEqualTo("pro");
        assertThat(a.at("/entitlements/features/projects").asInt()).isEqualTo(50);
        assertThat(a.at("/account/trialUsed").asBoolean()).isTrue();
        assertThat(outboxCount(acc, "subscription.started")).isEqualTo(1);
    }

    @Test
    @DisplayName("the trial is offered once: after cancelling, a new checkout has no trial")
    void trialOnlyOnce() throws Exception {
        UUID acc = newAccount("again");
        StripeEventFactory.Sub s = subscribed(acc, "starter_monthly", "trialing");
        deliver(subEvent("customer.subscription.deleted", s.withStatus("canceled"), Instant.now().plusSeconds(5)));
        assertThat(checkout(acc, "pro", "month").get("trialDays").asInt()).isZero();
    }

    @Test
    @DisplayName("starting a checkout while subscribed is a 409")
    void alreadySubscribed() throws Exception {
        UUID acc = newAccount("twice");
        subscribed(acc, "pro_monthly", "active");
        call(HttpMethod.POST, "/api/accounts/" + acc + "/checkout", Map.of("plan", "team", "interval", "month"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("upgrade is invoiced now (always_invoice); downgrade credits the next invoice")
    void prorationRules() throws Exception {
        UUID acc = newAccount("prorate");
        subscribed(acc, "pro_monthly", "active");
        JsonNode up = read(call(HttpMethod.POST, "/api/accounts/" + acc + "/subscription/preview",
                Map.of("plan", "team", "interval", "month")).andExpect(status().isOk()));
        assertThat(up.get("direction").asText()).isEqualTo("UPGRADE");
        assertThat(up.get("prorationBehavior").asText()).isEqualTo("always_invoice");
        assertThat(up.at("/preview/currency").asText()).isNotBlank();

        JsonNode down = read(call(HttpMethod.POST, "/api/accounts/" + acc + "/subscription/preview",
                Map.of("plan", "starter", "interval", "month")));
        assertThat(down.get("direction").asText()).isEqualTo("DOWNGRADE");
        assertThat(down.get("prorationBehavior").asText()).isEqualTo("create_prorations");

        call(HttpMethod.POST, "/api/accounts/" + acc + "/subscription/preview", Map.of("plan", "pro", "interval", "month"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("plan change is sent to Stripe (202) and applied locally only when the webhook confirms it")
    void changeConfirmedByWebhook() throws Exception {
        UUID acc = newAccount("change");
        StripeEventFactory.Sub s = subscribed(acc, "starter_monthly", "active");
        call(HttpMethod.POST, "/api/accounts/" + acc + "/subscription/change", Map.of("plan", "pro", "interval", "year"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("SENT_TO_STRIPE"));
        assertThat(account(acc).at("/subscription/priceLookupKey").asText()).isEqualTo("starter_monthly");

        var pro = catalog.price("pro_yearly").orElseThrow();
        deliver(subEvent("customer.subscription.updated",
                s.withPrice(pro.stripePriceId(), pro.lookupKey(), pro.unitAmount(), "year"), Instant.now().plusSeconds(5)));

        JsonNode a = account(acc);
        assertThat(a.at("/subscription/priceLookupKey").asText()).isEqualTo("pro_yearly");
        assertThat(a.at("/entitlements/features/audit_log").asBoolean()).isTrue();
        assertThat(outboxCount(acc, "subscription.plan_changed")).isEqualTo(1);
    }

    @Test
    @DisplayName("cancel at period end keeps access until the period ends, then drops to Free")
    void cancelAtPeriodEnd() throws Exception {
        UUID acc = newAccount("cancel");
        StripeEventFactory.Sub s = subscribed(acc, "pro_monthly", "active");
        call(HttpMethod.POST, "/api/accounts/" + acc + "/subscription/cancel", null).andExpect(status().isAccepted());

        deliver(subEvent("customer.subscription.updated", s.withCancelAtPeriodEnd(true), Instant.now().plusSeconds(5)));
        JsonNode a = account(acc);
        assertThat(a.at("/subscription/cancelAtPeriodEnd").asBoolean()).isTrue();
        assertThat(a.at("/entitlements/plan").asText()).isEqualTo("pro");
        assertThat(outboxCount(acc, "subscription.cancel_scheduled")).isEqualTo(1);
        call(HttpMethod.POST, "/api/accounts/" + acc + "/subscription/change", Map.of("plan", "team", "interval", "month"))
                .andExpect(status().isConflict());

        deliver(subEvent("customer.subscription.deleted", s.withStatus("canceled"), Instant.now().plusSeconds(10)));
        a = account(acc);
        assertThat(a.at("/subscription/status").asText()).isEqualTo("canceled");
        assertThat(a.at("/entitlements/plan").asText()).isEqualTo("free");
        assertThat(outboxCount(acc, "subscription.ended")).isEqualTo(1);
    }

    @Test
    @DisplayName("customer portal session is created for the Stripe customer")
    void portal() throws Exception {
        UUID acc = newAccount("portal");
        call(HttpMethod.POST, "/api/accounts/" + acc + "/portal", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("https://")));
    }

    @Test
    @DisplayName("entitlement check endpoint answers per feature")
    void featureCheck() throws Exception {
        UUID acc = newAccount("feature");
        call(HttpMethod.GET, "/api/accounts/" + acc + "/entitlements/sso", null)
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.plan").value("free"));
        subscribed(acc, "team_monthly", "active");
        call(HttpMethod.GET, "/api/accounts/" + acc + "/entitlements/sso", null)
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.plan").value("team"));
    }
}
