package com.finflow.ledger.application;

import java.time.Instant;
import java.util.UUID;

/** Une ligne du relevé d'un compte. */
public record EntryView(UUID transactionId, String kind, String side, long amountCents, Instant postedAt) {
}
