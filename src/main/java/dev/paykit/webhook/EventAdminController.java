package dev.paykit.webhook;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.paykit.outbox.OutboxMessage;
import dev.paykit.outbox.OutboxService;
import dev.paykit.web.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Admin")
public class EventAdminController {

    private final StripeEventRepository events;
    private final EventProcessor processor;
    private final OutboxService outbox;

    public EventAdminController(StripeEventRepository events, EventProcessor processor, OutboxService outbox) {
        this.events = events;
        this.processor = processor;
        this.outbox = outbox;
    }

    public record EventRow(String id, String type, String status, String note, UUID accountId, int attempts,
                           int deliveries, String lastError, String created, String receivedAt) {
        static EventRow of(StoredEvent e) {
            return new EventRow(e.id(), e.type(), e.status(), e.note(), e.accountId(), e.attempts(), e.deliveries(),
                    e.lastError(), String.valueOf(e.created()), String.valueOf(e.receivedAt()));
        }
    }

    @GetMapping("/api/admin/events")
    @Operation(summary = "Webhook event log (filter by status, type, account)")
    public List<EventRow> list(@RequestParam(required = false) String status, @RequestParam(required = false) String type,
                               @RequestParam(required = false) UUID accountId,
                               @RequestParam(defaultValue = "50") int limit) {
        return events.recent(Math.min(limit, 500), status, type, accountId).stream().map(EventRow::of).toList();
    }

    @GetMapping("/api/admin/events/{id}")
    @Operation(summary = "One stored event including its raw payload")
    public StoredEvent get(@PathVariable String id) {
        return events.find(id).orElseThrow(() -> new NotFoundException("Event " + id + " not found"));
    }

    @PostMapping("/api/admin/events/{id}/replay")
    @Operation(summary = "Re-process a stored event (e.g. a DEAD one after fixing the cause)")
    public Map<String, String> replay(@PathVariable String id) {
        if (events.resetForReplay(id) == 0) {
            throw new NotFoundException("Event " + id + " not found");
        }
        return Map.of("id", id, "status", processor.process(id));
    }

    @GetMapping("/api/admin/outbox")
    @Operation(summary = "Outbox messages (notifications) with delivery status")
    public List<OutboxMessage> outbox(@RequestParam(required = false) UUID accountId,
                                      @RequestParam(defaultValue = "50") int limit) {
        return outbox.recent(Math.min(limit, 500), accountId);
    }

    @PostMapping("/api/admin/outbox/{id}/retry")
    @Operation(summary = "Requeue a DEAD outbox message")
    public Map<String, Object> retry(@PathVariable long id) {
        if (outbox.retryDead(id) == 0) {
            throw new NotFoundException("No DEAD outbox message " + id);
        }
        return Map.of("id", id, "status", "PENDING");
    }
}
