package com.finflow.ledger.domain;

public enum Kind {
    /** Virement entre deux comptes clients (un paiement). */
    TRANSFER,
    /** Dépôt simulé : le compte de compensation est débité, le compte client crédité. */
    DEPOSIT,
    /** Inverse exact d'une transaction passée (un remboursement). */
    REVERSAL
}
