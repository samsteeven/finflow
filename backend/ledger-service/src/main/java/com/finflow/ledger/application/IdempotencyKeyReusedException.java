package com.finflow.ledger.application;

public class IdempotencyKeyReusedException extends RuntimeException {

    public static final String CODE = "CLE_REUTILISEE";

    public IdempotencyKeyReusedException(String key) {
        super("La clé " + key + " a déjà servi pour une opération différente");
    }
}
