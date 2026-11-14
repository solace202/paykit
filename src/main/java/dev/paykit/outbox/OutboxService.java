package dev.paykit.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.paykit.web.Json;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Writes notifications into the outbox table. Call it inside the same transaction as the state change: if the
 * transaction rolls back, the message disappears with it. The dedup key makes a redelivered webhook harmless.
 */
@Service
public class OutboxService {

    private final JdbcClient jdbc;
    private final Clock clock;

    public OutboxService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return true if a new message was queued, false if the dedup key already existed */
    public boolean enqueue(String topic, UUID accountId, String dedupKey, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>(payload);
        if (accountId != null) {
            jdbc.sql("select email from accounts where id = ?").param(accountId).query(String.class).optional()
                    .ifPresent(email -> body.putIfAbsent("to", email));
        }
        return jdbc.sql("""
                insert into outbox_messages (topic, account_id, dedup_key, payload, next_attempt_at)
                values (?, ?, ?, cast(? as jsonb), ?) on conflict (dedup_key) do nothing""")
                .params(topic, accountId, dedupKey, Json.write(body), Timestamp.from(clock.instant())).update() == 1;
    }

    static OutboxMessage map(ResultSet rs, int i) throws SQLException {
        Timestamp sent = rs.getTimestamp("sent_at");
        return new OutboxMessage(rs.getLong("id"), rs.getString("topic"), rs.getObject("account_id", UUID.class),
                rs.getString("dedup_key"), Json.readMap(rs.getString("payload")), rs.getString("status"),
                rs.getInt("attempts"), rs.getString("last_error"), rs.getTimestamp("next_attempt_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(), sent == null ? null : sent.toInstant());
    }

    public List<OutboxMessage> recent(int limit, UUID accountId) {
        return jdbc.sql("""
                select * from outbox_messages where (cast(:account as uuid) is null or account_id = :account)
                order by id desc limit :limit""")
                .param("account", accountId).param("limit", limit).query(OutboxService::map).list();
    }

    /** Locks a batch of due messages; rows locked by another relay instance are skipped, not waited for. */
    List<OutboxMessage> claimDue(int batch, Instant now) {
        return jdbc.sql("""
                select * from outbox_messages where status = 'PENDING' and next_attempt_at <= ?
                order by id limit ? for update skip locked""").params(Timestamp.from(now), batch)
                .query(OutboxService::map).list();
    }

    void markSent(long id) {
        jdbc.sql("update outbox_messages set status = 'SENT', attempts = attempts + 1, sent_at = now(), last_error = null "
                + "where id = ?").param(id).update();
    }

    void markFailed(long id, int attempts, String error, Instant next, boolean dead) {
        jdbc.sql("""
                update outbox_messages set attempts = ?, last_error = ?, next_attempt_at = ?,
                    status = case when ? then 'DEAD' else 'PENDING' end where id = ?""")
                .params(attempts, error, Timestamp.from(next), dead, id).update();
    }

    public int retryDead(long id) {
        return jdbc.sql("update outbox_messages set status = 'PENDING', attempts = 0, next_attempt_at = now() "
                + "where id = ? and status = 'DEAD'").param(id).update();
    }
}
