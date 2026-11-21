package com.finflow.payment.application;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("Cette clé d'idempotence a déjà servi pour une autre demande");
    }
}
