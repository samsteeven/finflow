package com.finflow.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finflow.account.application.AccountService;
import com.finflow.account.domain.Account;
import com.finflow.account.domain.AccountRepository;
import com.finflow.account.domain.Limits;
import com.finflow.security.TenantContext;
import com.finflow.security.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Ce que la base garantit, même si le code applicatif se trompait. */
@SpringBootTest
class AccountDatabaseIT extends PostgresIntegrationTest {

    @Autowired AccountService service;
    @Autowired AccountRepository accounts;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    TenantId alpha;
    TenantId beta;
    UUID alphaAccount;
    UUID betaAccount;

    @BeforeEach
    void deux_comptes_dans_deux_organisations() {
        alpha = TenantId.of(UUID.randomUUID());
        beta = TenantId.of(UUID.randomUUID());
        alphaAccount = TenantContext.callAs(alpha, () -> open("Alpha")).getId();
        betaAccount = TenantContext.callAs(beta, () -> open("Beta")).getId();
    }

    @Test
    void un_compte_d_une_autre_organisation_est_invisible_meme_par_son_identifiant() {
        TenantContext.runAs(alpha, () -> tx.executeWithoutResult(s -> {
            assertThat(accounts.findById(betaAccount)).isEmpty();
            assertThat(accounts.findById(alphaAccount)).isPresent();
            assertThat(jdbc.queryForObject("select count(*) from accounts where id = ?", Long.class, betaAccount))
                    .isZero();
        }));
        assertThat(jdbc.queryForObject("select count(*) from accounts", Long.class)).isZero();
    }

    @Test
    void un_compte_ne_peut_pas_etre_ouvert_pour_une_autre_organisation() {
        assertThatThrownBy(() -> TenantContext.runAs(alpha, () -> tx.executeWithoutResult(s -> jdbc.update("""
                insert into accounts (id, tenant_id, name, type, currency, per_transaction_limit_cents,
                                      daily_limit_cents, created_by)
                values (?, ?, 'Intrus', 'CURRENT', 'EUR', 100, 100, 'x')""", UUID.randomUUID(), beta.value()))))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void la_base_refuse_une_autre_devise_et_des_limites_incoherentes() {
        TenantContext.runAs(alpha, () -> {
            assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                    jdbc.update("update accounts set currency = 'USD' where id = ?", alphaAccount)))
                    .rootCause().hasMessageContaining("accounts_currency_check");
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                    "update accounts set daily_limit_cents = 1 where id = ?", alphaAccount)))
                    .rootCause().hasMessageContaining("limite_journaliere_au_moins_limite_par_operation");
        });
    }

    @Test
    void un_compte_cloture_ne_peut_plus_etre_modifie_meme_en_sql() {
        TenantContext.runAs(alpha, () -> {
            service.close(alphaAccount);
            assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                    jdbc.update("update accounts set status = 'ACTIVE' where id = ?", alphaAccount)))
                    .rootCause().hasMessageContaining("est clôturé");
        });
    }

    @Test
    void le_role_applicatif_ne_peut_pas_supprimer_un_compte() {
        assertThatThrownBy(() -> TenantContext.runAs(alpha, () -> tx.executeWithoutResult(s ->
                jdbc.update("delete from accounts where id = ?", alphaAccount))))
                .rootCause().hasMessageContaining("permission denied");
    }

    private Account open(String name) {
        return service.open(name, Account.Type.CURRENT, new Limits(1_000, 5_000), "admin");
    }
}
