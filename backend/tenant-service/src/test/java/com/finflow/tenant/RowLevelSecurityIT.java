package com.finflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finflow.security.TenantContext;
import com.finflow.security.TenantId;
import com.finflow.security.jpa.TenantAwareJpaTransactionManager;
import com.finflow.tenant.application.TenantService;
import com.finflow.tenant.domain.MembershipRepository;
import com.finflow.tenant.domain.Tenant;
import com.finflow.tenant.domain.TenantRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L'isolation tient en base, indépendamment du code applicatif (ADR-005). Une seule connexion
 * dans le pool : chaque test réutilise la connexion de la transaction précédente, ce qui rend
 * visible toute fuite de contexte.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=1")
class RowLevelSecurityIT extends PostgresIntegrationTest {

    @Autowired TenantService service;
    @Autowired TenantRepository tenants;
    @Autowired MembershipRepository memberships;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    TenantId alpha;
    TenantId beta;

    @BeforeEach
    void deux_organisations() {
        alpha = TenantId.of(create("alpha").getId());
        beta = TenantId.of(create("beta").getId());
    }

    @Test
    void le_gestionnaire_de_transactions_pose_le_contexte_tenant() {
        assertThat(transactionManager).isInstanceOf(TenantAwareJpaTransactionManager.class);
    }

    @Test
    void une_organisation_ne_lit_pas_une_autre_meme_par_son_identifiant() {
        TenantContext.runAs(alpha, () -> tx.executeWithoutResult(s -> {
            assertThat(tenants.findById(beta.value())).isEmpty();
            assertThat(tenants.findAll()).extracting(Tenant::getId).containsExactly(alpha.value());
            assertThat(memberships.findAll()).allSatisfy(m -> assertThat(m.getTenantId()).isEqualTo(alpha.value()));
            assertThat(jdbc.queryForObject("select count(*) from memberships where tenant_id = ?",
                    Long.class, beta.value())).isZero();
        }));
    }

    @Test
    void sans_contexte_aucune_ligne_n_est_visible() {
        tx.executeWithoutResult(s -> {
            assertThat(tenants.count()).isZero();
            assertThat(memberships.count()).isZero();
        });
        // Hors transaction Spring, en autocommit : la RLS refuse tout de la même façon.
        assertThat(jdbc.queryForObject("select count(*) from tenants", Long.class)).isZero();
    }

    @Test
    void une_ecriture_pour_un_autre_tenant_est_refusee_par_la_base() {
        assertThatThrownBy(() -> TenantContext.runAs(alpha, () -> tx.executeWithoutResult(s -> jdbc.update(
                "insert into memberships (id, tenant_id, user_id, role) values (?, ?, 'intrus', 'TENANT_ADMIN')",
                UUID.randomUUID(), beta.value()))))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void une_mise_a_jour_ne_peut_pas_deplacer_une_ligne_vers_un_autre_tenant() {
        assertThatThrownBy(() -> TenantContext.runAs(alpha, () -> tx.executeWithoutResult(s -> jdbc.update(
                "update memberships set tenant_id = ? where tenant_id = ?", beta.value(), alpha.value()))))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void le_contexte_ne_fuit_pas_vers_la_transaction_suivante_sur_la_meme_connexion() {
        Long visibleAvecAlpha = TenantContext.callAs(alpha,
                () -> tx.execute(s -> jdbc.queryForObject("select count(*) from tenants", Long.class)));
        assertThat(visibleAvecAlpha).isEqualTo(1);

        tx.executeWithoutResult(s -> {
            assertThat(jdbc.queryForObject("select count(*) from tenants", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select coalesce(current_setting('app.tenant_id', true), '')",
                    String.class)).isEmpty();
        });
        assertThat(jdbc.queryForObject("select coalesce(current_setting('app.tenant_id', true), '')",
                String.class)).isEmpty();
    }

    @Test
    void le_role_applicatif_ne_peut_ni_contourner_ni_desactiver_la_rls() {
        assertThat(jdbc.queryForMap("select rolsuper, rolbypassrls from pg_roles where rolname = current_user"))
                .containsEntry("rolsuper", false)
                .containsEntry("rolbypassrls", false);
        assertThat(jdbc.queryForObject("select tableowner from pg_tables where tablename = 'tenants'", String.class))
                .isEqualTo("finflow_tenant_owner");

        assertThatThrownBy(() -> jdbc.execute((java.sql.Connection c) -> {
            try (var st = c.createStatement()) {
                st.execute("set row_security = off");
                st.executeQuery("select count(*) from tenants");
            } finally {
                try (var st = c.createStatement()) {
                    st.execute("reset row_security");
                }
            }
            return null;
        })).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void le_role_applicatif_ne_peut_pas_supprimer() {
        assertThatThrownBy(() -> TenantContext.runAs(alpha, () -> tx.executeWithoutResult(s ->
                jdbc.update("delete from memberships where tenant_id = ?", alpha.value()))))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }

    private Tenant create(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return service.create("Organisation " + slug, slug, Tenant.Plan.SANDBOX, "admin-" + slug);
    }
}
