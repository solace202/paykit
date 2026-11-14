package dev.paykit.billing;

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
public class SubscriptionRepository {

    private final JdbcClient jdbc;

    public SubscriptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    static SubscriptionRecord map(ResultSet rs, int i) throws SQLException {
        return new SubscriptionRecord(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getString("stripe_subscription_id"), rs.getString("stripe_item_id"),
                rs.getString("stripe_customer_id"), rs.getString("plan_code"), rs.getString("price_lookup_key"),
                rs.getString("status"), ts(rs, "trial_end"), ts(rs, "current_period_end"),
                rs.getBoolean("cancel_at_period_end"), ts(rs, "canceled_at"), ts(rs, "last_event_created"),
                ts(rs, "updated_at"));
    }

    public Optional<SubscriptionRecord> findByAccount(UUID accountId) {
        return jdbc.sql("select * from subscriptions where account_id = ?").param(accountId)
                .query(SubscriptionRepository::map).optional();
    }

    public Optional<SubscriptionRecord> findByStripeId(String stripeSubscriptionId) {
        return jdbc.sql("select * from subscriptions where stripe_subscription_id = ?").param(stripeSubscriptionId)
                .query(SubscriptionRepository::map).optional();
    }

    /** Row-locks the account's subscription so concurrent webhooks for it apply one after another. */
    public Optional<SubscriptionRecord> lockByAccount(UUID accountId) {
        return jdbc.sql("select * from subscriptions where account_id = ? for update").param(accountId)
                .query(SubscriptionRepository::map).optional();
    }

    public List<SubscriptionRecord> byStatus(String status) {
        return jdbc.sql("select * from subscriptions where status = ?").param(status)
                .query(SubscriptionRepository::map).list();
    }

    /**
     * One subscription per account: a new Stripe subscription for the same account replaces the old row
     * (e.g. re-subscribing after cancellation).
     */
    public void upsert(SubscriptionRecord s) {
        jdbc.sql("""
                insert into subscriptions (id, account_id, stripe_subscription_id, stripe_item_id, stripe_customer_id,
                    plan_code, price_lookup_key, status, trial_end, current_period_end, cancel_at_period_end,
                    canceled_at, last_event_created, updated_at)
                values (:id, :account, :sub, :item, :customer, :plan, :lookup, :status, :trialEnd, :periodEnd,
                    :cancelAtEnd, :canceledAt, :eventCreated, now())
                on conflict (account_id) do update set
                    stripe_subscription_id = excluded.stripe_subscription_id,
                    stripe_item_id = excluded.stripe_item_id,
                    stripe_customer_id = excluded.stripe_customer_id,
                    plan_code = excluded.plan_code,
                    price_lookup_key = excluded.price_lookup_key,
                    status = excluded.status,
                    trial_end = excluded.trial_end,
                    current_period_end = excluded.current_period_end,
                    cancel_at_period_end = excluded.cancel_at_period_end,
                    canceled_at = excluded.canceled_at,
                    last_event_created = excluded.last_event_created,
                    updated_at = now()""")
                .param("id", s.id()).param("account", s.accountId()).param("sub", s.stripeSubscriptionId())
                .param("item", s.stripeItemId()).param("customer", s.stripeCustomerId()).param("plan", s.planCode())
                .param("lookup", s.priceLookupKey()).param("status", s.status())
                .param("trialEnd", t(s.trialEnd())).param("periodEnd", t(s.currentPeriodEnd()))
                .param("cancelAtEnd", s.cancelAtPeriodEnd()).param("canceledAt", t(s.canceledAt()))
                .param("eventCreated", t(s.lastEventCreated()))
                .update();
    }

    static Timestamp t(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
