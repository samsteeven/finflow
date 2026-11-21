package com.finflow.payment.application;

import java.time.Instant;
import java.util.UUID;

/** Un événement tel qu'il est conservé, avec son enveloppe : la base de la traçabilité. */
public record StoredEvent(int version, UUID eventId, String eventType, int eventVersion, Object payload,
                          String correlationId, UUID causationId, Instant occurredAt) {
}
