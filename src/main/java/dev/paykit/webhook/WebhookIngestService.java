package dev.paykit.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

/** Verify → store (dedup on event id) → process. */
@Service
public class WebhookIngestService {

    public record Receipt(String eventId, String type, boolean duplicate, String status) {
    }

    private final WebhookVerifier verifier;
    private final StripeEventRepository events;
    private final EventProcessor processor;
    private final ObjectMapper json;

    public WebhookIngestService(WebhookVerifier verifier, StripeEventRepository events, EventProcessor processor,
                                ObjectMapper json) {
        this.verifier = verifier;
        this.events = events;
        this.processor = processor;
        this.json = json;
    }

    public Receipt ingest(String payload, String signatureHeader) {
        verifier.verify(payload, signatureHeader);
        JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Payload is not JSON");
        }
        StripeEvent event = StripeEvent.of(root);
        if (event.id().isBlank() || event.type().isBlank()) {
            throw new IllegalArgumentException("Not a Stripe event");
        }
        boolean inserted = events.insertIfAbsent(event, payload);
        if (!inserted) {
            String status = events.find(event.id()).map(StoredEvent::status).orElse("UNKNOWN");
            return new Receipt(event.id(), event.type(), true, status);
        }
        return new Receipt(event.id(), event.type(), false, processor.process(event.id()));
    }
}
