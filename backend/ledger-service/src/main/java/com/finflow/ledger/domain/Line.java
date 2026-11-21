package com.finflow.ledger.domain;

import java.util.UUID;

public record Line(UUID accountId, Side side, long amountCents) {

    public Line {
        if (amountCents <= 0) {
            throw new InvalidPostingException("Le montant d'une ligne doit être strictement positif");
        }
    }

    /** Effet de la ligne sur le solde du compte. */
    public long delta() {
        return side == Side.CREDIT ? amountCents : -amountCents;
    }
}
