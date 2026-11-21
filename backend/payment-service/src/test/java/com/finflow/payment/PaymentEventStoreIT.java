package com.finflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finflow.payment.application.ConcurrencyConflictException;
import com.finflow.payment.application.PaymentEventStore;
import com.finflow.payment.domain.Payment;
import com.finflow.security.TenantContext;
import com.finflow.security.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** L'event store vu de la base : concurrence optimiste, snapshots, immuabilité, isolation. */
@SpringBootTest
class PaymentEventStoreIT extends PaymentIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-11-20T18:00:00Z");

    @Autowired PaymentEventStore store;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    TenantId tenant;
    UUID paymentId;

    @BeforeEach
    void un_paiement_en_validation() {
        tenant = TenantId.of(UUID.randomUUID());
        Payment payment = Payment.initiate(UUID.randomUUID(), tenant.value(), UUID.randomUUID(), UUID.randomUUID(),
                1_000, "EUR", null, "alice", T0);
        paymentId = payment.id();
        inTenant(() -> tx.executeWithoutResult(s -> store.append(payment, "corr")));
    }

    @Test
    void deux_ecritures_concurrentes_de_la_meme_version_ne_passent_pas_toutes_les_deux() {
        inTenant(() -> {
            Payment first = store.load(paymentId).orElseThrow();
            Payment second = store.load(paymentId).orElseThrow();
            first.validate(T0);
            second.reject("REGLE", "détail", T0);

            tx.executeWithoutResult(s -> store.append(first, "corr"));
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> store.append(second, "corr")))
                    .isInstanceOf(ConcurrencyConflictException.class);
            assertThat(store.load(paymentId).orElseThrow().status().name()).isEqualTo("PROCESSING");
        });
    }

    @Test
    void un_snapshot_est_pris_toutes_les_cinq_versions_et_la_lecture_repart_de_lui() {
        inTenant(() -> {
            Payment payment = store.load(paymentId).orElseThrow();
            payment.validate(T0);
            save(payment);
            payment.fail("Ledger indisponible", T0);
            save(payment);
            payment.retry(T0);
            save(payment);
            assertThat((Integer) tx.execute(s -> jdbc.queryForObject(
                    "select version from payment_snapshots where payment_id = ?", Integer.class, paymentId)))
                    .isEqualTo(5);

            payment.complete(UUID.randomUUID(), T0.plusSeconds(1));
            save(payment);

            Payment fromSnapshot = store.load(paymentId).orElseThrow();
            assertThat(fromSnapshot.state()).isEqualTo(payment.state());
            assertThat(fromSnapshot.version()).isEqualTo(6);
        });

        // Sans snapshot, le rejeu complet du journal donne exactement le même état.
        superuser("delete from payment_snapshots where payment_id = '" + paymentId + "'");
        inTenant(() -> assertThat(store.load(paymentId).orElseThrow().state().status().name())
                .isEqualTo("COMPLETED"));
        inTenant(() -> assertThat(store.load(paymentId).orElseThrow().version()).isEqualTo(6));
    }

    @Test
    void la_lecture_repart_bien_du_snapshot_et_non_du_debut_du_journal() {
        inTenant(() -> {
            Payment payment = store.load(paymentId).orElseThrow();
            payment.validate(T0);
            save(payment);
            payment.fail("Ledger indisponible", T0);
            save(payment);
            payment.retry(T0);
            save(payment);
        });
        // Un snapshot altéré en superutilisateur se retrouve dans la lecture : c'est bien lui qui sert.
        superuser("update payment_snapshots set state = jsonb_set(state, '{retries}', '2') where payment_id = '"
                + paymentId + "'");
        inTenant(() -> assertThat(store.load(paymentId).orElseThrow().state().retries()).isEqualTo(2));
    }

    @Test
    void la_projection_n_applique_jamais_une_version_plus_ancienne_que_la_sienne() {
        // La ligne de résumé est déjà « en avance » (version 10) : une écriture de version 3 ne doit
        // pas la faire reculer.
        superuser("update payment_summaries set version = 10, status = 'COMPLETED' where payment_id = '"
                + paymentId + "'");
        inTenant(() -> {
            Payment payment = store.load(paymentId).orElseThrow();
            payment.validate(T0);
            save(payment);
            assertThat((java.util.Map<String, Object>) tx.execute(s -> jdbc.queryForMap(
                    "select version, status from payment_summaries where payment_id = ?", paymentId)))
                    .containsEntry("version", 10)
                    .containsEntry("status", "COMPLETED");
        });
    }

    private static void superuser(String sql) {
        try (var c = DATABASE.superuserConnection(); var st = c.createStatement()) {
            st.execute(sql);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void un_evenement_ne_peut_etre_ni_modifie_ni_supprime() {
        inTenant(() -> {
            assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                    jdbc.update("update payment_events set payload = '{}' where payment_id = ?", paymentId)))
                    .rootCause().hasMessageContaining("permission denied");
            assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                    jdbc.update("delete from payment_events where payment_id = ?", paymentId)))
                    .rootCause().hasMessageContaining("permission denied");
            assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                    jdbc.update("delete from idempotency_keys")))
                    .rootCause().hasMessageContaining("permission denied");
        });
    }

    @Test
    void une_autre_organisation_ne_lit_ni_n_ecrit_le_journal_d_un_paiement() {
        TenantId other = TenantId.of(UUID.randomUUID());
        TenantContext.runAs(other, () -> {
            assertThat(store.load(paymentId)).isEmpty();
            assertThat(store.history(paymentId)).isEmpty();
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                    insert into payment_events (payment_id, version, tenant_id, event_id, event_type, event_version,
                        payload, occurred_at)
                    values (?, 3, ?, ?, 'PaymentValidated', 1, '{}', now())""",
                    paymentId, tenant.value(), UUID.randomUUID())))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("row-level security");
        });
        assertThat(jdbc.queryForObject("select count(*) from payment_events", Long.class)).isZero();
    }

    private void save(Payment payment) {
        tx.executeWithoutResult(s -> store.append(payment, "corr"));
    }

    private void inTenant(Runnable work) {
        TenantContext.runAs(tenant, work);
    }
}
