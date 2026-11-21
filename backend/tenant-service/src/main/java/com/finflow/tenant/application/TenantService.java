package com.finflow.tenant.application;

import com.finflow.security.TenantContext;
import com.finflow.security.TenantId;
import com.finflow.tenant.domain.Membership;
import com.finflow.tenant.domain.MembershipRepository;
import com.finflow.tenant.domain.Tenant;
import com.finflow.tenant.domain.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TenantService {

    private final TenantRepository tenants;
    private final MembershipRepository memberships;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public TenantService(TenantRepository tenants, MembershipRepository memberships,
                         TransactionTemplate transactions, Clock clock) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Crée une organisation et son premier administrateur.
     *
     * <p>Opération de plateforme : l'appelant n'appartient pas à la nouvelle organisation. Le
     * tenant est déclaré explicitement pour la transaction, sinon la RLS refuserait l'insertion.
     */
    public Tenant create(String name, String slug, Tenant.Plan plan, String adminUserId) {
        TenantId tenantId = TenantId.of(UUID.randomUUID());
        Instant now = clock.instant();
        try {
            return TenantContext.callAs(tenantId, () -> transactions.execute(status -> {
                Tenant tenant = tenants.saveAndFlush(new Tenant(tenantId.value(), name.strip(), slug, plan, now));
                memberships.saveAndFlush(new Membership(UUID.randomUUID(), tenantId.value(), adminUserId,
                        Membership.Role.TENANT_ADMIN, now));
                return tenant;
            }));
        } catch (DataIntegrityViolationException e) {
            if (isSlugConflict(e)) {
                throw new SlugAlreadyUsedException(slug);
            }
            throw e;
        }
    }

    /** L'organisation de l'appelant, lue avec son propre contexte (RLS). */
    public Tenant current() {
        TenantId tenantId = TenantContext.require();
        return transactions.execute(status -> tenants.findById(tenantId.value()))
                .orElseThrow(() -> new TenantNotFoundException(tenantId.value()));
    }

    /**
     * Une organisation par identifiant. Celle d'un autre tenant répond « introuvable », comme
     * un identifiant qui n'existe pas : on ne révèle pas son existence.
     */
    public Tenant get(UUID id) {
        TenantId tenantId = TenantContext.require();
        if (!tenantId.value().equals(id)) {
            throw new TenantNotFoundException(id);
        }
        return current();
    }

    public List<Membership> members() {
        TenantId tenantId = TenantContext.require();
        return transactions.execute(status -> memberships.findByTenantIdOrderByCreatedAtAsc(tenantId.value()));
    }

    private static boolean isSlugConflict(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        return message.contains("tenants_slug_key");
    }
}
