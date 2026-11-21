package com.finflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finflow.security.TenantContext;
import com.finflow.security.TenantId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Les garanties du grand livre vues de la base, avec le rôle applicatif (ADR-011) : elles doivent
 * tenir même si le code Java se trompe, c'est ce que ces tests écrivent en SQL brut.
 */
@SpringBootTest
class LedgerDatabaseIT extends LedgerIntegrationTest {

    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    TenantId tenant;
    UUID clearing;
    UUID alice;
    UUID bruno;

    @BeforeEach
    void deux_comptes_dont_un_approvisionne() {
        tenant = TenantId.of(UUID.randomUUID());
        clearing = UUID.randomUUID();
        alice = UUID.randomUUID();
        bruno = UUID.randomUUID();
        inTenant(() -> {
            tx.executeWithoutResult(s -> {
                account(clearing, "CLEARING");
                account(alice, "CUSTOMER");
                account(bruno, "CUSTOMER");
            });
            // Dépôt de 10 000 centimes sur le compte d'Alice.
            post(UUID.randomUUID(), "DEPOSIT", null, line(clearing, "DEBIT", 10_000), line(alice, "CREDIT", 10_000));
        });
    }

    @Test
    void une_transaction_deséquilibree_est_refusee_au_commit() {
        inTenant(() -> assertThatThrownBy(() ->
                post(UUID.randomUUID(), "TRANSFER", null, line(alice, "DEBIT", 500), line(bruno, "CREDIT", 400)))
                .rootCause().hasMessageContaining("déséquilibrée"));
        inTenant(() -> assertThat(balance(alice)).isEqualTo(10_000));
        inTenant(() -> assertThat(balance(bruno)).isZero());
    }

    @Test
    void une_transaction_d_une_seule_ligne_est_refusee() {
        inTenant(() -> assertThatThrownBy(() ->
                post(UUID.randomUUID(), "TRANSFER", null, line(alice, "DEBIT", 500)))
                .rootCause().hasMessageContaining("au moins deux lignes"));
    }

    @Test
    void une_entete_sans_aucune_ligne_est_refusee() {
        inTenant(() -> assertThatThrownBy(() -> tx.executeWithoutResult(s -> header(UUID.randomUUID(), "TRANSFER", null)))
                .rootCause().hasMessageContaining("au moins deux lignes"));
    }

    @Test
    void une_ligne_ajoutee_apres_coup_a_une_transaction_validee_est_refusee() {
        UUID id = UUID.randomUUID();
        inTenant(() -> {
            post(id, "TRANSFER", null, line(alice, "DEBIT", 500), line(bruno, "CREDIT", 500));
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> entry(id, 3, line(alice, "DEBIT", 1))))
                    .rootCause().hasMessageContaining("déséquilibrée");
            assertThat(balance(alice)).isEqualTo(9_500);
        });
    }

    @Test
    void le_journal_ne_peut_etre_ni_modifie_ni_supprime() {
        UUID id = UUID.randomUUID();
        inTenant(() -> {
            post(id, "TRANSFER", null, line(alice, "DEBIT", 500), line(bruno, "CREDIT", 500));
            for (String sql : List.of(
                    "update journal_entries set amount_cents = 1 where transaction_id = '" + id + "'",
                    "delete from journal_entries where transaction_id = '" + id + "'",
                    "update journal_transactions set description = 'x' where id = '" + id + "'",
                    "delete from journal_transactions where id = '" + id + "'")) {
                assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(sql)))
                        .as(sql).rootCause().hasMessageContaining("permission denied");
            }
        });
    }

    @Test
    void un_solde_ne_se_modifie_ni_ne_se_fixe_a_la_creation() {
        inTenant(() -> {
            assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                    jdbc.update("update ledger_accounts set balance_cents = 1000000 where id = ?", alice)))
                    .rootCause().hasMessageContaining("permission denied");
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                    "insert into ledger_accounts (id, tenant_id, kind, currency, balance_cents) "
                            + "values (?, ?, 'CUSTOMER', 'EUR', 5000)", UUID.randomUUID(), tenant.value())))
                    .rootCause().hasMessageContaining("permission denied");
            assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                    jdbc.update("delete from ledger_accounts where id = ?", alice)))
                    .rootCause().hasMessageContaining("permission denied");
            assertThat(balance(alice)).isEqualTo(10_000);
        });
    }

    @Test
    void un_compte_client_ne_peut_pas_passer_sous_zero() {
        inTenant(() -> {
            assertThatThrownBy(() ->
                    post(UUID.randomUUID(), "TRANSFER", null, line(alice, "DEBIT", 10_001), line(bruno, "CREDIT", 10_001)))
                    .rootCause().hasMessageContaining("solde_non_negatif");
            assertThat(balance(alice)).isEqualTo(10_000);
            // Exactement le solde : autorisé.
            post(UUID.randomUUID(), "TRANSFER", null, line(alice, "DEBIT", 10_000), line(bruno, "CREDIT", 10_000));
            assertThat(balance(alice)).isZero();
        });
    }

    @Test
    void la_compensation_peut_etre_negative() {
        inTenant(() -> assertThat(balance(clearing)).isEqualTo(-10_000));
    }

    @Test
    void une_contrepassation_doit_etre_l_inverse_exact_de_l_original() {
        UUID original = UUID.randomUUID();
        inTenant(() -> {
            post(original, "TRANSFER", null, line(alice, "DEBIT", 500), line(bruno, "CREDIT", 500));

            // Montant différent : équilibrée, mais ce n'est pas l'inverse.
            assertThatThrownBy(() -> post(UUID.randomUUID(), "REVERSAL", original,
                    line(alice, "CREDIT", 400), line(bruno, "DEBIT", 400)))
                    .rootCause().hasMessageContaining("n'est pas l'inverse");
            // Mêmes sens que l'original : ce n'est pas un inverse non plus.
            assertThatThrownBy(() -> post(UUID.randomUUID(), "REVERSAL", original,
                    line(alice, "DEBIT", 500), line(bruno, "CREDIT", 500)))
                    .rootCause().hasMessageContaining("n'est pas l'inverse");

            UUID reversal = UUID.randomUUID();
            post(reversal, "REVERSAL", original, line(alice, "CREDIT", 500), line(bruno, "DEBIT", 500));
            assertThat(balance(alice)).isEqualTo(10_000);
            assertThat(balance(bruno)).isZero();

            // Une transaction ne se contrepasse qu'une fois, une contrepassation ne se contrepasse pas.
            assertThatThrownBy(() -> post(UUID.randomUUID(), "REVERSAL", original,
                    line(alice, "CREDIT", 500), line(bruno, "DEBIT", 500)))
                    .rootCause().hasMessageContaining("une_seule_contrepassation");
            assertThatThrownBy(() -> post(UUID.randomUUID(), "REVERSAL", reversal,
                    line(alice, "DEBIT", 500), line(bruno, "CREDIT", 500)))
                    .rootCause().hasMessageContaining("ne se contrepasse pas");
        });
    }

    @Test
    void une_cle_d_idempotence_ne_s_enregistre_qu_une_fois_par_organisation() {
        inTenant(() -> {
            tx.executeWithoutResult(s -> {
                header(UUID.randomUUID(), "DEPOSIT", null, "cle-unique");
                entry(lastHeader, 1, line(clearing, "DEBIT", 100));
                entry(lastHeader, 2, line(alice, "CREDIT", 100));
            });
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> header(UUID.randomUUID(), "DEPOSIT", null,
                    "cle-unique"))).rootCause().hasMessageContaining("idempotency_key");
        });
    }

    @Test
    void une_autre_organisation_ne_voit_ni_n_ecrit_le_grand_livre() {
        TenantId other = TenantId.of(UUID.randomUUID());
        TenantContext.runAs(other, () -> {
            assertThat(count("select count(*) from ledger_accounts")).isZero();
            assertThat(count("select count(*) from journal_entries")).isZero();
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                    "insert into ledger_accounts (id, tenant_id, kind, currency) values (?, ?, 'CUSTOMER', 'EUR')",
                    UUID.randomUUID(), tenant.value()))).rootCause().hasMessageContaining("row-level security");
            // Une écriture de l'autre organisation sur un compte d'Alpha : refusée, solde intact.
            UUID tx2 = UUID.randomUUID();
            assertThatThrownBy(() -> post(tx2, "DEPOSIT", null, line(alice, "CREDIT", 1), line(UUID.randomUUID(), "DEBIT", 1)))
                    .isInstanceOf(DataAccessException.class);
        });
        inTenant(() -> assertThat(balance(alice)).isEqualTo(10_000));
    }

    @Test
    void sans_contexte_d_organisation_rien_n_est_visible() {
        assertThat(jdbc.queryForObject("select count(*) from ledger_accounts", Long.class)).isZero();
    }

    @Test
    void les_soldes_correspondent_toujours_a_la_somme_des_ecritures() {
        inTenant(() -> {
            post(UUID.randomUUID(), "TRANSFER", null, line(alice, "DEBIT", 1_234), line(bruno, "CREDIT", 1_234));
            assertThat(inconsistent()).isEmpty();
        });
        // Le contrôle voit bien un solde trafiqué (sinon il ne prouverait rien).
        superuser("update ledger_accounts set balance_cents = balance_cents + 1 where id = '" + bruno + "'");
        inTenant(() -> assertThat(inconsistent()).containsExactly(bruno));
        superuser("update ledger_accounts set balance_cents = balance_cents - 1 where id = '" + bruno + "'");
    }

    // --- aides -----------------------------------------------------------------------------------

    record L(UUID account, String side, long amount) {
    }

    UUID lastHeader;

    static L line(UUID account, String side, long amount) {
        return new L(account, side, amount);
    }

    void account(UUID id, String kind) {
        jdbc.update("insert into ledger_accounts (id, tenant_id, kind, currency) values (?, ?, ?, 'EUR')",
                id, tenant.value(), kind);
    }

    void header(UUID id, String kind, UUID reversalOf) {
        header(id, kind, reversalOf, "cle-" + id);
    }

    void header(UUID id, String kind, UUID reversalOf, String key) {
        lastHeader = id;
        jdbc.update("""
                insert into journal_transactions (id, tenant_id, kind, idempotency_key, request_hash, reversal_of)
                values (?, ?, ?, ?, 'h', ?)""", id, tenant.value(), kind, key, reversalOf);
    }

    void entry(UUID transaction, int lineNo, L line) {
        jdbc.update("""
                insert into journal_entries (transaction_id, line_no, tenant_id, account_id, side, amount_cents, currency)
                values (?, ?, ?, ?, ?, ?, 'EUR')""", transaction, lineNo, tenant.value(), line.account(), line.side(),
                line.amount());
    }

    /** Une transaction complète, validée au commit. */
    void post(UUID id, String kind, UUID reversalOf, L... lines) {
        tx.executeWithoutResult(s -> {
            header(id, kind, reversalOf);
            int n = 0;
            for (L line : lines) {
                entry(id, ++n, line);
            }
        });
    }

    long count(String sql) {
        Long value = tx.execute(s -> jdbc.queryForObject(sql, Long.class));
        return value;
    }

    List<UUID> inconsistent() {
        return tx.execute(s -> jdbc.queryForList("select account_id from comptes_incoherents()", UUID.class));
    }

    long balance(UUID account) {
        Long value = tx.execute(s -> jdbc.queryForObject(
                "select balance_cents from ledger_accounts where id = ?", Long.class, account));
        return value;
    }

    void inTenant(Runnable work) {
        TenantContext.runAs(tenant, work);
    }

    private static void superuser(String sql) {
        try (var c = DATABASE.superuserConnection(); var st = c.createStatement()) {
            st.execute(sql);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
