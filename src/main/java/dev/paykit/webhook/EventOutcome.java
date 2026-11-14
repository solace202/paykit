package dev.paykit.webhook;

import java.util.UUID;

public record EventOutcome(String status, String note, UUID accountId) {

    public static EventOutcome processed(UUID accountId, String note) {
        return new EventOutcome("PROCESSED", note, accountId);
    }

    public static EventOutcome ignored(String note) {
        return new EventOutcome("IGNORED", note, null);
    }

    public static EventOutcome ignored(UUID accountId, String note) {
        return new EventOutcome("IGNORED", note, accountId);
    }
}
