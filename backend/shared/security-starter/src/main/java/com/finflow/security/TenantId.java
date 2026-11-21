package com.finflow.security;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Identifiant d'organisation. Toujours issu d'une source de confiance : le jeton validé,
 * ou un traitement système qui le déclare explicitement ({@link TenantContext#runAs}).
 */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "value");
    }

    public static TenantId of(UUID value) {
        return new TenantId(value);
    }

    /** Lit un identifiant textuel ; une valeur absente ou mal formée donne un résultat vide. */
    public static Optional<TenantId> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TenantId(UUID.fromString(raw.strip())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
