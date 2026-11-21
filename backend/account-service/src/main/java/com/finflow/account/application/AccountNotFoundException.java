package com.finflow.account.application;

import java.util.UUID;

/** Compte inexistant ou appartenant à une autre organisation : les deux cas sont indiscernables. */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID id) {
        super("Compte introuvable : " + id);
    }
}
