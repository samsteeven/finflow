package com.finflow.payment.domain;

import java.time.Instant;
import java.util.UUID;

/** État complet d'un paiement à une version donnée : contenu des snapshots et des lectures. */
public record PaymentState(
        UUID id,
        UUID tenantId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long amountCents,
        String currency,
        String description,
        String requestedBy,
        PaymentStatus status,
        Payment.Rejection rejection,
        String failureReason,
        int retries,
        UUID ledgerTransactionId,
        UUID refundLedgerTransactionId,
        UUID refundId,
        String refundRejection,
        Instant createdAt,
        Instant updatedAt,
        int version) {
}
