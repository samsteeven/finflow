package com.finflow.payment.application;

import java.util.UUID;

public record IdempotencyRecord(String requestHash, UUID paymentId) {
}
