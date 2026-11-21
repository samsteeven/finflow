package com.finflow.account.domain;

public class InvalidLimitsException extends RuntimeException {

    public InvalidLimitsException(String message) {
        super(message);
    }
}
