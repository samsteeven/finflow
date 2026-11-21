package com.finflow.payment.infrastructure;

import com.finflow.payment.application.IdempotencyRecord;
import com.finflow.payment.application.PaymentQueries;
import com.finflow.payment.application.PaymentSummary;
import com.finflow.payment.domain.PaymentStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Idempotence, verrou par compte source et lectures du modèle de résumé. */
@Repository
class JdbcPaymentQueries implements PaymentQueries {

    private final JdbcClient jdbc;

    JdbcPaymentQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordIdempotencyKey(UUID tenantId, String key, String requestHash, UUID paymentId) {
        jdbc.sql("""
                        insert into idempotency_keys (tenant_id, idempotency_key, request_hash, payment_id)
                        values (:tenantId, :key, :hash, :paymentId)""")
                .param("tenantId", tenantId)
                .param("key", key)
                .param("hash", requestHash)
                .param("paymentId", paymentId)
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> findIdempotencyKey(String key) {
        return jdbc.sql("select request_hash, payment_id from idempotency_keys where idempotency_key = :key")
                .param("key", key)
                .query((rs, i) -> new IdempotencyRecord(rs.getString("request_hash"),
                        rs.getObject("payment_id", UUID.class)))
                .optional();
    }

    /**
     * Sérialise les validations d'un même compte source jusqu'à la fin de la transaction
     * (ADR-010). La clé est dérivée de l'identifiant du compte.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockSourceAccount(UUID accountId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:account, 0))")
                .param("account", accountId.toString())
                .query(Object.class).single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long committedTodayCents(UUID sourceAccountId, Instant startOfDay) {
        return jdbc.sql("""
                        select coalesce(sum(amount_cents), 0) from payment_summaries
                        where source_account_id = :account and created_at >= :start and status in (:statuses)""")
                .param("account", sourceAccountId)
                .param("start", Timestamp.from(startOfDay))
                .param("statuses", PaymentStatus.COUNTED_IN_DAILY_LIMIT.stream().map(Enum::name).toList())
                .query(Long.class).single();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentSummary> list(PaymentStatus status, int page, int size) {
        return jdbc.sql("""
                        select payment_id, source_account_id, destination_account_id, amount_cents, currency, status,
                               created_at, updated_at
                        from payment_summaries
                        where (cast(:status as varchar) is null or status = :status)
                        order by created_at desc, payment_id
                        limit :size offset :offset""")
                .param("status", status == null ? null : status.name())
                .param("size", size)
                .param("offset", (long) page * size)
                .query((rs, i) -> new PaymentSummary(
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("source_account_id", UUID.class),
                        rs.getObject("destination_account_id", UUID.class),
                        rs.getLong("amount_cents"),
                        rs.getString("currency"),
                        PaymentStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(PaymentStatus status) {
        return jdbc.sql("""
                        select count(*) from payment_summaries
                        where (cast(:status as varchar) is null or status = :status)""")
                .param("status", status == null ? null : status.name())
                .query(Long.class).single();
    }
}
