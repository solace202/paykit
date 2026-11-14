package dev.paykit.catalog;

import java.util.List;
import java.util.Optional;

import dev.paykit.web.Json;
import dev.paykit.web.NotFoundException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {

    private final JdbcClient jdbc;

    public CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Plan> plans() {
        return jdbc.sql("select * from plans order by tier").query((rs, i) -> new Plan(rs.getString("code"),
                rs.getString("name"), rs.getInt("tier"), rs.getInt("trial_days"), Json.readMap(rs.getString("features")),
                rs.getString("stripe_product_id"))).list();
    }

    public Plan plan(String code) {
        return plans().stream().filter(p -> p.code().equals(code)).findFirst()
                .orElseThrow(() -> new NotFoundException("Unknown plan " + code));
    }

    public List<PlanPrice> prices() {
        return jdbc.sql("""
                select pp.* from plan_prices pp join plans p on p.code = pp.plan_code
                order by p.tier, pp.billing_interval""").query((rs, i) -> new PlanPrice(rs.getString("lookup_key"),
                rs.getString("plan_code"), rs.getString("billing_interval"), rs.getLong("unit_amount"),
                rs.getString("currency"), rs.getString("stripe_price_id"))).list();
    }

    public Optional<PlanPrice> price(String lookupKey) {
        return prices().stream().filter(p -> p.lookupKey().equals(lookupKey)).findFirst();
    }

    public Optional<PlanPrice> priceByStripeId(String stripePriceId) {
        return prices().stream().filter(p -> stripePriceId.equals(p.stripePriceId())).findFirst();
    }

    public Optional<PlanPrice> price(String planCode, String interval) {
        return prices().stream().filter(p -> p.planCode().equals(planCode) && p.interval().equals(interval)).findFirst();
    }

    public void setStripeProductId(String planCode, String productId) {
        jdbc.sql("update plans set stripe_product_id = ? where code = ?").params(productId, planCode).update();
    }

    public void setStripePriceId(String lookupKey, String priceId) {
        jdbc.sql("update plan_prices set stripe_price_id = ? where lookup_key = ?").params(priceId, lookupKey).update();
    }
}
