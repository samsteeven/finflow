package com.finflow.payment.application;

/** Jeton du compte technique de Payment, pour agir hors d'une requête utilisateur (ADR-015). */
public interface ServiceTokenProvider {

    /** @throws IllegalStateException si le jeton ne peut pas être obtenu : l'appelant réessaiera plus tard */
    String token();
}
