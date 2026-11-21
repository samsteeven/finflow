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

    /** Un paiement dont la saga attend une reprise, avec son organisation. */
    record Due(UUID paymentId, UUID tenantId) {
    }

    /**
     * Paiements de toutes les organisations dont la saga est restée en suspens plus longtemps que
     * le délai (doublé à chaque reprise). Ne renvoie que des identifiants (ADR-015).
     */
    List<Due> dueForRecovery(int baseDelaySeconds, int maxRetries, int limit);
}
