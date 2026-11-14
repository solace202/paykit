package dev.paykit.webhook;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StripeEventRepository {

    private final JdbcClient jdbc;

    public StripeEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static Instant ts(ResultSet rs, String c) throws SQLException {
        Timestamp t = rs.getTimestamp(c);
        return t == null ? null : t.toInstant();
    }

    static StoredEvent map(ResultSet rs, int i) throws SQLException {
        return new StoredEvent(rs.getString("id"), rs.getString("type"), rs.getString("api_version"),
                rs.getBoolean("livemode"), ts(rs, "created"), rs.getString("payload"),
                rs.getObject("account_id", UUID.class), rs.getString("status"), rs.getString("note"),
                rs.getInt("attempts"), rs.getInt("deliveries"), rs.getString("last_error"), ts(rs, "next_attempt_at"),
                ts(rs, "received_at"), ts(rs, "processed_at"));
    }

    /**
     * Stores a new event. A redelivery of a known event id only bumps the delivery counter.
     *
     * @return true if the event is new
     */
    public boolean insertIfAbsent(StripeEvent e, String payload) {
        return jdbc.sql("""
                insert into stripe_events (id, type, api_version, livemode, created, payload)
                values (?, ?, ?, ?, ?, cast(? as jsonb))
                on conflict (id) do update set deliveries = stripe_events.deliveries + 1
                returning (xmax = 0) as inserted""")
                .params(e.id(), e.type(), e.apiVersion(), e.livemode(), Timestamp.from(e.created()), payload)
                .query(Boolean.class).single();
    }

    public Optional<StoredEvent> find(String id) {
        return jdbc.sql("select * from stripe_events where id = ?").param(id).query(StripeEventRepository::map).optional();
    }

    Optional<StoredEvent> lock(String id) {
        return jdbc.sql("select * from stripe_events where id = ? for update").param(id)
                .query(StripeEventRepository::map).optional();
    }

    void markDone(String id, EventOutcome o, int attempts) {
        jdbc.sql("""
                update stripe_events set status = ?, note = ?, account_id = coalesce(?, account_id), attempts = ?,
                    last_error = null, next_attempt_at = null, processed_at = now() where id = ?""")
                .params(o.status(), o.note(), o.accountId(), attempts, id).update();
    }

    void markFailed(String id, int attempts, String error, Instant next, boolean dead) {
        jdbc.sql("""
                update stripe_events set status = case when ? then 'DEAD' else 'FAILED' end, attempts = ?,
                    last_error = ?, next_attempt_at = ? where id = ?""")
                .params(dead, attempts, error, dead ? null : Timestamp.from(next), id).update();
    }

    public int resetForReplay(String id) {
        return jdbc.sql("update stripe_events set status = 'RECEIVED', next_attempt_at = null where id = ?")
                .param(id).update();
    }

    public List<String> dueForRetry(Instant now, int limit) {
        return jdbc.sql("""
                select id from stripe_events where status = 'FAILED' and next_attempt_at <= ?
                order by next_attempt_at limit ?""").params(Timestamp.from(now), limit).query(String.class).list();
    }

    public List<StoredEvent> recent(int limit, String status, String type, UUID accountId) {
        return jdbc.sql("""
                select * from stripe_events
                where (cast(:status as text) is null or status = :status)
                  and (cast(:type as text) is null or type = :type)
                  and (cast(:account as uuid) is null or account_id = :account)
                order by received_at desc, created desc limit :limit""")
                .param("status", status).param("type", type).param("account", accountId).param("limit", limit)
                .query(StripeEventRepository::map).list();
    }
}
