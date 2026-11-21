package com.finflow.account.domain;

import java.util.UUID;

public class IllegalAccountTransitionException extends RuntimeException {

    public IllegalAccountTransitionException(UUID id, String action, Account.Status status) {
        super("Impossible de " + action + " le compte " + id + " : il est " + status);
    }
}
