package com.finflow.tenant.domain;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Entité dont l'identifiant est attribué par l'application. Sans {@link Persistable},
 * Spring Data ferait un {@code SELECT} avant chaque insertion pour deviner s'il s'agit d'une
 * nouvelle ligne.
 */
@MappedSuperclass
abstract class AssignedIdEntity<ID> implements Persistable<ID> {

    @Transient
    private boolean persisted;

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.persisted = true;
    }
}
