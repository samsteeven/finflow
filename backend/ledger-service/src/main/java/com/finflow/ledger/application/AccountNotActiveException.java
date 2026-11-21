package com.finflow.ledger.application;

import java.util.UUID;

public class AccountNotActiveException extends RuntimeException {

    public AccountNotActiveException(UUID accountId, String status) {
        super("Le compte " + accountId + " n'est pas actif (" + status + ")");
    }
}
