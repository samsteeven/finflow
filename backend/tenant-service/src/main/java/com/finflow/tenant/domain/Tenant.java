package com.finflow.tenant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class Tenant extends AssignedIdEntity<UUID> {

    public enum Status { ACTIVE, SUSPENDED }

    public enum Plan { SANDBOX, BUSINESS, ENTERPRISE }

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 40, unique = true)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Plan plan;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Tenant() {
    }

    public Tenant(UUID id, String name, String slug, Plan plan, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.plan = plan;
        this.status = Status.ACTIVE;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public Status getStatus() {
        return status;
    }

    public Plan getPlan() {
        return plan;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
