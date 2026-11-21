package com.finflow.account.domain;

/** Limites d'un compte, en centimes. La base impose les mêmes règles (contraintes CHECK). */
public record Limits(long perTransactionCents, long dailyCents) {

    public Limits {
        if (perTransactionCents <= 0 || dailyCents <= 0) {
            throw new InvalidLimitsException("Les limites doivent être strictement positives");
        }
        if (dailyCents < perTransactionCents) {
            throw new InvalidLimitsException(
                    "La limite journalière ne peut pas être inférieure à la limite par opération");
        }
    }
}
