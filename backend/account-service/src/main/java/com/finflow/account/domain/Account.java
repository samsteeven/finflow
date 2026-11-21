package com.finflow.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Compte financier d'une organisation : identité, statut et limites. Il ne porte pas de solde ;
 * la vérité comptable appartient au Ledger.
 */
@Entity
@Table(name = "accounts")
public class Account implements Persistable<UUID> {

    public enum Type { CURRENT, SAVINGS }

    public enum Status { ACTIVE, FROZEN, CLOSED }

    public static final String CURRENCY = "EUR";

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private Type type;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "per_transaction_limit_cents", nullable = false)
    private long perTransactionLimitCents;

    @Column(name = "daily_limit_cents", nullable = false)
    private long dailyLimitCents;

    @Column(name = "created_by", nullable = false, length = 64, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @Transient
    private boolean persisted;

    protected Account() {
    }

    public Account(UUID id, UUID tenantId, String name, Type type, Limits limits, String createdBy, Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name.strip();
        this.type = type;
        this.currency = CURRENCY;
        this.status = Status.ACTIVE;
        this.perTransactionLimitCents = limits.perTransactionCents();
        this.dailyLimitCents = limits.dailyCents();
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void changeLimits(Limits limits, Instant now) {
        requireNotClosed("modifier les limites");
        this.perTransactionLimitCents = limits.perTransactionCents();
        this.dailyLimitCents = limits.dailyCents();
        this.updatedAt = now;
    }

    public void freeze(Instant now) {
        transition(Status.ACTIVE, Status.FROZEN, "geler", now);
    }

    public void unfreeze(Instant now) {
        transition(Status.FROZEN, Status.ACTIVE, "dégeler", now);
    }

    public void close(Instant now) {
        requireNotClosed("clôturer");
        this.status = Status.CLOSED;
        this.updatedAt = now;
    }

    private void transition(Status from, Status to, String action, Instant now) {
        if (status != from) {
            throw new IllegalAccountTransitionException(id, action, status);
        }
        this.status = to;
        this.updatedAt = now;
    }

    private void requireNotClosed(String action) {
        if (status == Status.CLOSED) {
            throw new IllegalAccountTransitionException(id, action, status);
        }
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.persisted = true;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public Type getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public Status getStatus() {
        return status;
    }

    public Limits getLimits() {
        return new Limits(perTransactionLimitCents, dailyLimitCents);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
