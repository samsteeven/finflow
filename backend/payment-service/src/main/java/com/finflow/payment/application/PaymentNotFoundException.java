package com.finflow.payment.application;

import java.util.UUID;

/** Paiement inexistant ou appartenant à une autre organisation : les deux cas sont indiscernables. */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID id) {
        super("Paiement introuvable : " + id);
    }
}
