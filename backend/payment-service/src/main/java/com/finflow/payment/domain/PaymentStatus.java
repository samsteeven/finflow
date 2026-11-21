package com.finflow.payment.domain;

public enum PaymentStatus {
    CREATED,
    VALIDATING,
    REJECTED,
    PROCESSING,
    COMPLETED,
    FAILED,
    REFUND_PENDING,
    REFUNDED;

    /** Statuts qui engagent le compte source et comptent dans sa limite journalière. */
    public static final java.util.Set<PaymentStatus> COUNTED_IN_DAILY_LIMIT =
            java.util.EnumSet.of(PROCESSING, COMPLETED, REFUND_PENDING);
}
