package com.finflow.payment.domain;

import java.util.UUID;

public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(UUID id, String action, PaymentStatus status, String reason) {
        super("Impossible de " + action + " le paiement " + id + " dans l'état " + status
                + (reason == null ? "" : " : " + reason));
    }
}
