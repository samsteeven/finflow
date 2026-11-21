package com.finflow.payment.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Faits du cycle de vie d'un paiement. L'ensemble est scellé : ajouter un événement oblige à
 * traiter son effet dans {@link Payment#apply} (le compilateur vérifie l'exhaustivité du switch).
 *
 * <p>Les montants sont en centimes. Chaque événement a une version de schéma, conservée avec
 * lui, pour que ses consommateurs (Kafka, incrément 4) puissent évoluer.
 */
public sealed interface PaymentEvent {

    int SCHEMA_VERSION = 1;

    UUID paymentId();

    Instant occurredAt();

    default String type() {
        return getClass().getSimpleName();
    }

    record PaymentCreated(UUID paymentId, UUID tenantId, UUID sourceAccountId, UUID destinationAccountId,
                          long amountCents, String currency, String description, String requestedBy,
                          Instant occurredAt) implements PaymentEvent {
    }

    record PaymentValidationStarted(UUID paymentId, Instant occurredAt) implements PaymentEvent {
    }

    /** Comptes et limites acceptés : le paiement part en comptabilisation. */
    record PaymentValidated(UUID paymentId, Instant occurredAt) implements PaymentEvent {
    }

    /** Refus métier, explicable : règle déclenchée et détail (valeur observée, seuil). */
    record PaymentRejected(UUID paymentId, String rule, String detail, Instant occurredAt) implements PaymentEvent {
    }

    record PaymentCompleted(UUID paymentId, UUID ledgerTransactionId, Instant occurredAt) implements PaymentEvent {
    }

    /** Échec technique de la comptabilisation ; le paiement peut être repris. */
    record PaymentFailed(UUID paymentId, String reason, Instant occurredAt) implements PaymentEvent {
    }

    record PaymentRetried(UUID paymentId, int attempt, Instant occurredAt) implements PaymentEvent {
    }

    record RefundRequested(UUID paymentId, String reason, Instant occurredAt) implements PaymentEvent {
    }

    record RefundCompleted(UUID paymentId, UUID ledgerTransactionId, Instant occurredAt) implements PaymentEvent {
    }
}
