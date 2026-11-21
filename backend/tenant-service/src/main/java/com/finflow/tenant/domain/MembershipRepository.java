package com.finflow.tenant.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    /** Le filtre explicite double la RLS : les deux protections doivent céder pour fuir. */
    List<Membership> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);
}
