package com.finflow.payment.application;

import java.util.UUID;

/** Ce que Payment lit d'un compte, tel que le renvoie account-service. */
public record AccountView(UUID id, String currency, String status, Limits limits) {

    public record Limits(long perTransactionCents, long dailyCents) {
    }

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
