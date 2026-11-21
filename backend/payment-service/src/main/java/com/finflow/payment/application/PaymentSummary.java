package com.finflow.payment.application;

import com.finflow.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

public record PaymentSummary(UUID id, UUID sourceAccountId, UUID destinationAccountId, long amountCents,
                             String currency, PaymentStatus status, Instant createdAt, Instant updatedAt) {
}
