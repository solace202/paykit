package dev.paykit.entitlement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import dev.paykit.billing.SubscriptionRepository;
import dev.paykit.catalog.CatalogRepository;
import dev.paykit.catalog.Plan;
import dev.paykit.dunning.DunningService;
import dev.paykit.web.Json;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materialised entitlements: one row per (account, feature). Your application reads these instead of
 * reasoning about Stripe statuses; they are recomputed whenever a webhook changes the subscription.
 */
@Service
public class EntitlementService {

    public record EntitlementView(UUID accountId, String plan, String reason, Map<String, Object> features) {
    }

    public record FeatureCheck(String feature, Object value, boolean enabled, String plan) {
    }

    private final JdbcClient jdbc;
    private final SubscriptionRepository subscriptions;
    private final CatalogRepository catalog;
    private final DunningService dunning;

    public EntitlementService(JdbcClient jdbc, SubscriptionRepository subscriptions, CatalogRepository catalog,
                              DunningService dunning) {
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
        this.catalog = catalog;
        this.dunning = dunning;
    }

    EntitlementPolicy.Decision decide(UUID accountId) {
        return EntitlementPolicy.decide(subscriptions.findByAccount(accountId), dunning.open(accountId), dunning.now());
    }

    @Transactional
    public EntitlementView recompute(UUID accountId) {
        EntitlementPolicy.Decision d = decide(accountId);
        Plan plan = catalog.plan(d.planCode());
        jdbc.sql("delete from entitlements where account_id = ?").param(accountId).update();
        plan.features().forEach((feature, value) -> jdbc.sql("""
                insert into entitlements (account_id, feature, value, plan_code) values (?, ?, ?, ?)""")
                .params(accountId, feature, Json.write(value), plan.code()).update());
        return new EntitlementView(accountId, plan.code(), d.reason(), new LinkedHashMap<>(plan.features()));
    }

    public EntitlementView get(UUID accountId) {
        Map<String, Object> features = new LinkedHashMap<>();
        String[] plan = {Plan.FREE};
        jdbc.sql("select feature, value, plan_code from entitlements where account_id = ? order by feature")
                .param(accountId).query(rs -> {
                    features.put(rs.getString("feature"), parse(rs.getString("value")));
                    plan[0] = rs.getString("plan_code");
                });
        return new EntitlementView(accountId, plan[0], decide(accountId).reason(), features);
    }

    public FeatureCheck check(UUID accountId, String feature) {
        EntitlementView view = get(accountId);
        Object value = view.features().get(feature);
        boolean enabled = value instanceof Boolean b ? b : value instanceof Number n && n.longValue() != 0;
        return new FeatureCheck(feature, value, enabled, view.plan());
    }

    private static Object parse(String json) {
        try {
            return Json.mapper().readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
