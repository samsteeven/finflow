package com.finflow.tenant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Rattachement d'un utilisateur Keycloak (son {@code sub}) à une organisation. */
@Entity
@Table(name = "memberships")
public class Membership extends AssignedIdEntity<UUID> {

    public enum Role { TENANT_ADMIN, TENANT_MEMBER }

    public enum Status { ACTIVE, REVOKED }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Membership() {
    }

    public Membership(UUID id, UUID tenantId, String userId, Role role, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.role = role;
        this.status = Status.ACTIVE;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
