package com.finflow.ledger.application;

import java.util.UUID;

/** La transaction a déjà été contrepassée, ou est elle-même une contrepassation. */
public class NotReversibleException extends RuntimeException {

    public static final String CODE = "NON_CONTREPASSABLE";

    public NotReversibleException(UUID id, String reason) {
        super("La transaction " + id + " ne peut pas être contrepassée : " + reason);
    }
}
