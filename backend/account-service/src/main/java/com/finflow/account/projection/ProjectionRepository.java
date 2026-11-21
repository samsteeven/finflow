package com.finflow.account.projection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Écritures et lectures des projections ; la RLS borne chaque requête à l'organisation (ADR-005). */
@Repository
class ProjectionRepository {

    private final JdbcClient jdbc;

    ProjectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Ajoute une variation au solde projeté. Un compte inconnu de cette organisation (la
     * compensation du Ledger, par exemple) est ignoré : la sélection ne renvoie aucune ligne.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void addToBalance(UUID accountId, long delta) {
        jdbc.sql("""
                        insert into account_balance_projection (account_id, tenant_id, balance_cents)
                        select a.id, a.tenant_id, :delta from accounts a where a.id = :id
                        on conflict (account_id) do update
                           set balance_cents = account_balance_projection.balance_cents + excluded.balance_cents,
                               updated_at = now()""")
                .param("id", accountId)
                .param("delta", delta)
                .update();
    }

    /**
     * La projection n'avance jamais à reculons : une version égale ou plus ancienne est ignorée.
     *
     * @return {@code true} si la projection a changé
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean upsertPayment(UUID paymentId, UUID tenantId, UUID source, UUID destination, long amountCents,
                          String status, long version) {
        return jdbc.sql("""
                        insert into payment_projection (payment_id, tenant_id, source_account_id,
                            destination_account_id, amount_cents, status, aggregate_version)
                        values (:id, :tenant, :source, :destination, :amount, :status, :version)
                        on conflict (payment_id) do update
                           set status = excluded.status, aggregate_version = excluded.aggregate_version,
                               updated_at = now()
                         where payment_projection.aggregate_version < excluded.aggregate_version""")
                .param("id", paymentId)
                .param("tenant", tenantId)
                .param("source", source)
                .param("destination", destination)
                .param("amount", amountCents)
                .param("status", status)
                .param("version", version)
                .update() == 1;
    }

    @Transactional(readOnly = true)
    Optional<Long> balance(UUID accountId) {
        return jdbc.sql("select balance_cents from account_balance_projection where account_id = :id")
                .param("id", accountId).query(Long.class).optional();
    }

    @Transactional(readOnly = true)
    List<PaymentActivity> payments(UUID accountId, int limit) {
        return jdbc.sql("""
                        select payment_id, source_account_id, destination_account_id, amount_cents, status,
                               aggregate_version, updated_at
                          from payment_projection
                         where source_account_id = :id or destination_account_id = :id
                         order by updated_at desc, payment_id limit :limit""")
                .param("id", accountId)
                .param("limit", limit)
                .query((rs, i) -> new PaymentActivity(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getLong(4), rs.getString(5), rs.getLong(6),
                        rs.getTimestamp(7).toInstant()))
                .list();
    }
}
