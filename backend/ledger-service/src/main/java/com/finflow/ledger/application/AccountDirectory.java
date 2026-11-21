package com.finflow.ledger.application;

import java.util.Optional;
import java.util.UUID;

/** Accès aux comptes (account-service), au nom de l'appelant. */
public interface AccountDirectory {

    /**
     * Le compte s'il existe dans l'organisation de l'appelant, vide sinon.
     *
     * @throws AccountServiceUnavailableException panne, délai dépassé ou erreur serveur : ce
     *     n'est pas une réponse métier, le paiement doit attendre (ADR-010)
     */
    Optional<AccountView> find(UUID accountId);
}
