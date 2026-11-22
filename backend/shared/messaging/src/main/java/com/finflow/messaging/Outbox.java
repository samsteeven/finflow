package com.finflow.messaging;

import com.finflow.events.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Écriture de l'événement dans la table outbox, dans la transaction qui change l'état métier
 * (ADR-013) : soit les deux sont validés, soit aucun. {@code MANDATORY} interdit de l'appeler
 * hors transaction, où la garantie serait perdue sans que rien ne le signale.
 */
public class Outbox {

    private final JdbcTemplate jdbc;
    private final java.util.function.Supplier<String> traceContext;

    public Outbox(JdbcTemplate jdbc) {
        this(jdbc, () -> null);
    }

    /** @param traceContext fournit le contexte de trace W3C courant (ou null) à conserver avec l'événement */
    public Outbox(JdbcTemplate jdbc, java.util.function.Supplier<String> traceContext) {
        this.jdbc = jdbc;
        this.traceContext = traceContext;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, EventEnvelope envelope) {
        jdbc.update("""
                insert into outbox (event_id, topic, message_key, payload, trace_context)
                values (?::uuid, ?, ?, ?, ?)
                """, envelope.getEventId(), topic, Envelopes.partitionKey(envelope), AvroJson.write(envelope),
                traceContext.get());
    }
}
