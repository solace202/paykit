package dev.paykit.dunning;

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
public class DunningRepository {

    private final JdbcClient jdbc;

    public DunningRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static Instant ts(ResultSet rs, String c) throws SQLException {
        Timestamp t = rs.getTimestamp(c);
        return t == null ? null : t.toInstant();
    }

    static Timestamp t(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    static DunningCase map(ResultSet rs, int i) throws SQLException {
        return new DunningCase(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getString("stripe_invoice_id"), rs.getString("status"), rs.getInt("failed_attempts"),
                ts(rs, "first_failed_at"), ts(rs, "last_failed_at"), ts(rs, "next_attempt_at"), ts(rs, "grace_until"),
                ts(rs, "closed_at"));
    }

    public Optional<DunningCase> open(UUID accountId) {
        return jdbc.sql("select * from dunning_cases where account_id = ? and status = 'OPEN'").param(accountId)
                .query(DunningRepository::map).optional();
    }

    public List<DunningCase> forAccount(UUID accountId) {
        return jdbc.sql("select * from dunning_cases where account_id = ? order by first_failed_at desc")
                .param(accountId).query(DunningRepository::map).list();
    }

    public List<DunningCase> openPastGrace(Instant now) {
        return jdbc.sql("select * from dunning_cases where status = 'OPEN' and grace_until <= ?").param(t(now))
                .query(DunningRepository::map).list();
    }

    public void insert(DunningCase c) {
        jdbc.sql("""
                insert into dunning_cases (id, account_id, stripe_invoice_id, status, failed_attempts, first_failed_at,
                    last_failed_at, next_attempt_at, grace_until)
                values (?, ?, ?, 'OPEN', ?, ?, ?, ?, ?)""")
                .params(c.id(), c.accountId(), c.stripeInvoiceId(), c.failedAttempts(), t(c.firstFailedAt()),
                        t(c.lastFailedAt()), t(c.nextAttemptAt()), t(c.graceUntil()))
                .update();
    }

    public void updateAttempt(UUID id, int attempts, Instant failedAt, Instant nextAttempt) {
        jdbc.sql("""
                update dunning_cases set failed_attempts = greatest(failed_attempts, ?), last_failed_at = ?,
                    next_attempt_at = ? where id = ?""").params(attempts, t(failedAt), t(nextAttempt), id).update();
    }

    public int close(UUID accountId, String status) {
        return jdbc.sql("update dunning_cases set status = ?, closed_at = now() where account_id = ? and status = 'OPEN'")
                .params(status, accountId).update();
    }
}
