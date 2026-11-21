package com.finflow.ledger.application;

import java.util.UUID;

/** Refus métier : un compte client ne peut pas passer sous zéro. */
public class InsufficientFundsException extends RuntimeException {

    public static final String CODE = "FONDS_INSUFFISANTS";

    private final UUID accountId;

    public InsufficientFundsException(UUID accountId, long availableCents, long requiredCents) {
        super("Solde insuffisant sur le compte " + accountId + " : " + availableCents + " centimes disponibles, "
                + requiredCents + " nécessaires");
        this.accountId = accountId;
    }

    public UUID accountId() {
        return accountId;
    }
}
