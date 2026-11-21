package com.finflow.ledger.domain;

/** Sens d'une écriture. Pour un compte client : un crédit augmente le solde, un débit le diminue. */
public enum Side {
    DEBIT, CREDIT;

    public Side opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
