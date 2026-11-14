package dev.paykit.webhook;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Webhooks")
public class StripeWebhookController {

    private final WebhookIngestService ingest;

    public StripeWebhookController(WebhookIngestService ingest) {
        this.ingest = ingest;
    }

    /**
     * Returns 200 as soon as the event is stored, even if processing failed (it is retried from the inbox);
     * only a bad signature or payload gets a 400, which makes Stripe retry the delivery.
     */
    @PostMapping(value = "/webhooks/stripe", consumes = "application/json")
    @Operation(summary = "Stripe webhook endpoint (signature verified, idempotent)")
    @SecurityRequirements
    public WebhookIngestService.Receipt receive(@RequestBody String payload,
                                                @RequestHeader(value = "Stripe-Signature", required = false) String sig) {
        return ingest.ingest(payload, sig);
    }
}
