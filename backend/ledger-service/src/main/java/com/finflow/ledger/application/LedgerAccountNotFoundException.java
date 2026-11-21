package com.finflow.ledger.application;

import java.util.UUID;

public class LedgerAccountNotFoundException extends RuntimeException {

    public LedgerAccountNotFoundException(UUID accountId) {
        super("Compte introuvable : " + accountId);
    }
}
