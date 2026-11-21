package com.finflow.security.jpa;

import com.finflow.security.TenantContext;
import com.finflow.security.TenantId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Pose le tenant courant dans la session PostgreSQL au début de chaque transaction.
 *
 * <p>{@code set_config(..., true)} limite la valeur à la transaction : quand la connexion
 * retourne au pool, le tenant disparaît et ne peut pas servir à l'utilisateur suivant
 * (ADR-005). Sans tenant, on pose une chaîne vide : les politiques RLS la traduisent en
 * {@code NULL} et ne laissent rien passer.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    static final String SET_TENANT_SQL = "select set_config('app.tenant_id', ?1, true)";

    public TenantAwareJpaTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        if (holder == null) {
            throw new IllegalStateException("Transaction JPA démarrée sans EntityManager lié");
        }
        EntityManager em = holder.getEntityManager();
        String tenant = TenantContext.current().map(TenantId::toString).orElse("");
        em.createNativeQuery(SET_TENANT_SQL).setParameter(1, tenant).getSingleResult();
    }
}
