package com.finflow.ledger.domain;

public class InvalidPostingException extends RuntimeException {

    public InvalidPostingException(String message) {
        super(message);
    }
}
