package com.finflow.tenant.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Les requêtes ne filtrent pas sur le tenant : la RLS ne renvoie que celui de la transaction. */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {
}
