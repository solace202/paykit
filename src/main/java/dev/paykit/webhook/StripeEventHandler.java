package dev.paykit.webhook;

import java.util.Set;

/** Handles one or more Stripe event types. Runs inside the processing transaction. */
public interface StripeEventHandler {

    Set<String> types();

    EventOutcome handle(StripeEvent event);
}
