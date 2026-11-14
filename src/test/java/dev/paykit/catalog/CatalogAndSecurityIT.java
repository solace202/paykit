package dev.paykit.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.paykit.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

@DisplayName("Catalog sync and API security")
class CatalogAndSecurityIT extends IntegrationTest {

    @Test
    @DisplayName("catalog sync creates or matches every price in Stripe by lookup_key and stores the ids")
    void sync() throws Exception {
        JsonNode first = read(call(HttpMethod.POST, "/api/admin/catalog/sync", null).andExpect(status().isOk()));
        assertThat(first).hasSize(6);
        assertThat(first.findValuesAsText("lookupKey")).contains("starter_monthly", "pro_yearly", "team_monthly");
        assertThat(first.findValuesAsText("stripePriceId")).allMatch(id -> id.startsWith("price_"));
        // running it again is safe
        call(HttpMethod.POST, "/api/admin/catalog/sync", null).andExpect(status().isOk());
        assertThat(catalog.prices()).allMatch(p -> p.stripePriceId() != null);
    }

    @Test
    @DisplayName("plans are public; account and admin APIs require credentials")
    void security() throws Exception {
        mvc.perform(get("/api/plans")).andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("free"));
        mvc.perform(get("/api/admin/events")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@y.z\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("invalid input is a 400 problem; duplicate email is a 409")
    void validation() throws Exception {
        call(HttpMethod.POST, "/api/accounts", java.util.Map.of("email", "not-an-email"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.email").exists());
        call(HttpMethod.POST, "/api/accounts", java.util.Map.of("email", "same@example.com")).andExpect(status().isCreated());
        call(HttpMethod.POST, "/api/accounts", java.util.Map.of("email", "same@example.com"))
                .andExpect(status().isConflict());
    }
}
