package dev.paykit.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.paykit.web.NotFoundException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static Account map(ResultSet rs, int i) throws SQLException {
        return new Account(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("name"),
                rs.getString("stripe_customer_id"), rs.getBoolean("trial_used"),
                rs.getTimestamp("created_at").toInstant());
    }

    public void insert(UUID id, String email, String name) {
        jdbc.sql("insert into accounts (id, email, name) values (?, ?, ?)").params(id, email, name).update();
    }

    public Optional<Account> find(UUID id) {
        return jdbc.sql("select * from accounts where id = ?").param(id).query(AccountRepository::map).optional();
    }

    public Account get(UUID id) {
        return find(id).orElseThrow(() -> new NotFoundException("Account " + id + " not found"));
    }

    public Optional<Account> findByEmail(String email) {
        return jdbc.sql("select * from accounts where lower(email) = lower(?)").param(email)
                .query(AccountRepository::map).optional();
    }

    /** Several local accounts can share a fixture customer id against stripe-mock, so take the newest. */
    public Optional<Account> findByStripeCustomer(String customerId) {
        return jdbc.sql("select * from accounts where stripe_customer_id = ? order by created_at desc limit 1")
                .param(customerId).query(AccountRepository::map).optional();
    }

    public List<Account> recent(int limit) {
        return jdbc.sql("select * from accounts order by created_at desc limit ?").param(limit)
                .query(AccountRepository::map).list();
    }

    public void setStripeCustomer(UUID id, String customerId) {
        jdbc.sql("update accounts set stripe_customer_id = ? where id = ?").params(customerId, id).update();
    }

    public void markTrialUsed(UUID id) {
        jdbc.sql("update accounts set trial_used = true where id = ?").param(id).update();
    }
}
