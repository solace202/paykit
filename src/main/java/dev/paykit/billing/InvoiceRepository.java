package dev.paykit.billing;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InvoiceRepository {

    public record InvoiceRow(String id, String subscription, String status, String billingReason, long amountDue,
                             long amountPaid, String currency, int attemptCount, Instant nextPaymentAttempt,
                             String hostedInvoiceUrl, Instant created) {
    }

    private final JdbcClient jdbc;

    public InvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<InvoiceRow> forAccount(UUID accountId) {
        return jdbc.sql("select * from invoices where account_id = ? order by created desc, updated_at desc")
                .param(accountId).query((rs, i) -> {
                    Timestamp next = rs.getTimestamp("next_payment_attempt");
                    return new InvoiceRow(rs.getString("stripe_invoice_id"), rs.getString("stripe_subscription_id"),
                            rs.getString("status"), rs.getString("billing_reason"), rs.getLong("amount_due"),
                            rs.getLong("amount_paid"), rs.getString("currency"), rs.getInt("attempt_count"),
                            next == null ? null : next.toInstant(), rs.getString("hosted_invoice_url"),
                            rs.getTimestamp("created").toInstant());
                }).list();
    }
}
