package dev.paykit.billing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CheckoutRepository {

    public record CheckoutSession(UUID id, UUID accountId, String priceLookupKey, String stripeSessionId, String url,
                                  int trialDays, String status, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    public CheckoutRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static CheckoutSession map(ResultSet rs, int i) throws SQLException {
        return new CheckoutSession(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getString("price_lookup_key"), rs.getString("stripe_session_id"), rs.getString("url"),
                rs.getInt("trial_days"), rs.getString("status"), rs.getTimestamp("created_at").toInstant());
    }

    public void insert(UUID id, UUID accountId, String lookupKey, String sessionId, String url, int trialDays) {
        jdbc.sql("""
                insert into checkout_sessions (id, account_id, price_lookup_key, stripe_session_id, url, trial_days)
                values (?, ?, ?, ?, ?, ?)""").params(id, accountId, lookupKey, sessionId, url, trialDays).update();
    }

    public Optional<CheckoutSession> find(UUID id) {
        return jdbc.sql("select * from checkout_sessions where id = ?").param(id).query(CheckoutRepository::map).optional();
    }

    public int setStatus(UUID id, String status) {
        return jdbc.sql("""
                update checkout_sessions set status = ?, completed_at = case when ? = 'COMPLETED' then now() end
                where id = ? and status = 'OPEN'""").params(status, status, id).update();
    }
}
