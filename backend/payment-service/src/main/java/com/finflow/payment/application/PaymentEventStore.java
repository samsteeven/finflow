package com.finflow.payment.application;

import com.finflow.payment.domain.Payment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentEventStore {

    /**
     * Écrit les événements en attente à la suite de la version attendue, met à jour la
     * projection et, au besoin, le snapshot. Lève {@link ConcurrencyConflictException} si un
     * autre traitement a écrit entre-temps.
     */
    void append(Payment payment, String correlationId);

    Optional<Payment> load(UUID paymentId);

    List<StoredEvent> history(UUID paymentId);
}
