package dev.paykit.outbox;

/** Delivers one outbox message (email service, Slack, CRM...). Throw to have it retried with backoff. */
public interface NotificationSender {

    void send(OutboxMessage message) throws Exception;
}
