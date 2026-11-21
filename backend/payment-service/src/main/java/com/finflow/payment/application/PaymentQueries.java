package com.finflow.payment.application;

import com.finflow.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentQueries {

    void recordIdempotencyKey(UUID tenantId, String key, String requestHash, UUID paymentId);

    Optional<IdempotencyRecord> findIdempotencyKey(String key);

    void lockSourceAccount(UUID accountId);

    /** Montant déjà engagé aujourd'hui sur le compte source (statuts comptés dans la limite). */
    long committedTodayCents(UUID sourceAccountId, Instant startOfDay);

    List<PaymentSummary> list(PaymentStatus status, int page, int size);

    long count(PaymentStatus status);
}
