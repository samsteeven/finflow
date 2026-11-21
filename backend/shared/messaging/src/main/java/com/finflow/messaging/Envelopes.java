package com.finflow.messaging;

import com.finflow.events.EventEnvelope;
import java.time.Instant;
import java.util.UUID;

/** Construction de l'enveloppe commune ; la clé de partition est {@code tenantId:aggregateId}. */
public final class Envelopes {

    private Envelopes() {
    }

    public static EventEnvelope of(UUID eventId, String producer, String eventType, UUID aggregateId, long aggregateVersion,
                                   UUID tenantId, String correlationId, String causationId, Instant occurredAt,
                                   Object payload) {
        return EventEnvelope.newBuilder()
                .setEventId(eventId.toString())
                .setEventType(eventType)
                .setEventVersion(1)
                .setAggregateId(aggregateId.toString())
                .setAggregateVersion(aggregateVersion)
                .setTenantId(tenantId.toString())
                .setCorrelationId(correlationId)
                .setCausationId(causationId)
                .setOccurredAt(occurredAt.toEpochMilli())
                .setProducer(producer)
                .setPayload(payload)
                .build();
    }

    public static String partitionKey(EventEnvelope envelope) {
        return envelope.getTenantId() + ":" + envelope.getAggregateId();
    }
}
