package com.finflow.ledger.application;

import java.util.UUID;

public record LedgerAccount(UUID id, String kind, String currency, long balanceCents) {

    public boolean clearing() {
        return "CLEARING".equals(kind);
    }
}
