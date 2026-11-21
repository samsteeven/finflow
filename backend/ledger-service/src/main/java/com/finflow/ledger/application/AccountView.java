package com.finflow.ledger.application;

import java.util.UUID;

/** Ce que Ledger lit d'un compte, tel que le renvoie account-service. */
public record AccountView(UUID id, String currency, String status) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
