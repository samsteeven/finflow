package com.finflow.account.projection;

import java.time.Instant;
import java.util.UUID;

public record PaymentActivity(UUID paymentId, UUID sourceAccountId, UUID destinationAccountId, long amountCents,
                              String status, long aggregateVersion, Instant updatedAt) {
}
