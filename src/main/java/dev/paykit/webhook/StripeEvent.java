package dev.paykit.webhook;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A verified Stripe event. The payload is read as JSON rather than through the SDK model classes, so the handlers
 * work across Stripe API versions (e.g. {@code current_period_end} moved from Subscription to its items in 2025).
 */
public record StripeEvent(String id, String type, Instant created, String apiVersion, boolean livemode,
                          JsonNode object, JsonNode previousAttributes) {

    public static StripeEvent of(JsonNode root) {
        JsonNode data = root.path("data");
        return new StripeEvent(root.path("id").asText(), root.path("type").asText(),
                Instant.ofEpochSecond(root.path("created").asLong()), text(root, "api_version"),
                root.path("livemode").asBoolean(false), data.path("object"), data.path("previous_attributes"));
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
