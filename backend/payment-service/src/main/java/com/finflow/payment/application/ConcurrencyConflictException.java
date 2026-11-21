package com.finflow.payment.application;

import java.util.UUID;

/** Un autre traitement a écrit une version du paiement entre la lecture et l'écriture. */
public class ConcurrencyConflictException extends RuntimeException {

    public ConcurrencyConflictException(UUID paymentId, int expectedVersion) {
        super("Le paiement " + paymentId + " a changé après la version " + expectedVersion);
    }
}
