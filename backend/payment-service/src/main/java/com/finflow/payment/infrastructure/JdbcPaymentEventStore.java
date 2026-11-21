package com.finflow.payment.infrastructure;

import com.finflow.payment.application.ConcurrencyConflictException;
import com.finflow.payment.application.PaymentEventStore;
import com.finflow.payment.application.StoredEvent;
import com.finflow.payment.domain.Payment;
import com.finflow.payment.domain.PaymentEvent;
import com.finflow.payment.domain.PaymentState;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event store JDBC (ADR-008). Les écritures se font dans la transaction de l'appelant, qui
 * doit en ouvrir une : événements, projection et snapshot sont validés ensemble ou pas du tout.
 */
@Repository
class JdbcPaymentEventStore implements PaymentEventStore {

    static final int SNAPSHOT_EVERY = 5;

    private final JdbcClient jdbc;
    private final EventSerializer serializer;

    JdbcPaymentEventStore(JdbcClient jdbc, EventSerializer serializer) {
        this.jdbc = jdbc;
        this.serializer = serializer;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(Payment payment, String correlationId) {
        List<PaymentEvent> events = payment.pendingEvents();
        if (events.isEmpty()) {
            return;
        }
        int version = payment.expectedVersion();
        UUID causation = null;
        try {
            for (PaymentEvent event : events) {
                version++;
                UUID eventId = UUID.randomUUID();
                jdbc.sql("""
                                insert into payment_events (payment_id, version, tenant_id, event_id, event_type,
                                    event_version, payload, correlation_id, causation_id, occurred_at)
                                values (:paymentId, :version, :tenantId, :eventId, :type, :schemaVersion,
                                    cast(:payload as jsonb), :correlationId, :causationId, :occurredAt)""")
                        .param("paymentId", payment.id())
                        .param("version", version)
                        .param("tenantId", payment.tenantId())
                        .param("eventId", eventId)
                        .param("type", event.type())
                        .param("schemaVersion", PaymentEvent.SCHEMA_VERSION)
                        .param("payload", serializer.write(event))
                        .param("correlationId", correlationId)
                        .param("causationId", causation)
                        .param("occurredAt", Timestamp.from(event.occurredAt()))
                        .update();
                causation = eventId;
            }
        } catch (DuplicateKeyException e) {
            throw new ConcurrencyConflictException(payment.id(), payment.expectedVersion());
        }
        project(payment.state());
        if (payment.version() / SNAPSHOT_EVERY > payment.expectedVersion() / SNAPSHOT_EVERY) {
            snapshot(payment.state());
        }
        payment.markCommitted();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> load(UUID paymentId) {
        Optional<PaymentState> snapshot = jdbc.sql("select state from payment_snapshots where payment_id = :id")
                .param("id", paymentId)
                .query(String.class).optional()
                .map(serializer::readState);
        int after = snapshot.map(PaymentState::version).orElse(0);
        List<PaymentEvent> events = jdbc.sql("""
                        select event_type, payload from payment_events
                        where payment_id = :id and version > :after order by version""")
                .param("id", paymentId)
                .param("after", after)
                .query((rs, i) -> serializer.readEvent(rs.getString("event_type"), rs.getString("payload")))
                .list();
        if (snapshot.isEmpty() && events.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Payment.replay(snapshot.orElse(null), events));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredEvent> history(UUID paymentId) {
        return jdbc.sql("""
                        select version, event_id, event_type, event_version, payload, correlation_id, causation_id,
                               occurred_at
                        from payment_events where payment_id = :id order by version""")
                .param("id", paymentId)
                .query((rs, i) -> new StoredEvent(
                        rs.getInt("version"),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getInt("event_version"),
                        serializer.readTree(rs.getString("payload")),
                        rs.getString("correlation_id"),
                        rs.getObject("causation_id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant()))
                .list();
    }

    /** La projection n'avance jamais à reculons : une version plus ancienne est ignorée. */
    private void project(PaymentState s) {
        jdbc.sql("""
                        insert into payment_summaries (payment_id, tenant_id, source_account_id,
                            destination_account_id, amount_cents, currency, status, version, created_at, updated_at)
                        values (:id, :tenantId, :source, :destination, :amount, :currency, :status, :version,
                            :createdAt, :updatedAt)
                        on conflict (payment_id) do update
                            set status = excluded.status, version = excluded.version, updated_at = excluded.updated_at
                            where payment_summaries.version < excluded.version""")
                .param("id", s.id())
                .param("tenantId", s.tenantId())
                .param("source", s.sourceAccountId())
                .param("destination", s.destinationAccountId())
                .param("amount", s.amountCents())
                .param("currency", s.currency())
                .param("status", s.status().name())
                .param("version", s.version())
                .param("createdAt", Timestamp.from(s.createdAt()))
                .param("updatedAt", Timestamp.from(s.updatedAt()))
                .update();
    }

    private void snapshot(PaymentState s) {
        jdbc.sql("""
                        insert into payment_snapshots (payment_id, tenant_id, version, state)
                        values (:id, :tenantId, :version, cast(:state as jsonb))
                        on conflict (payment_id) do update
                            set version = excluded.version, state = excluded.state, taken_at = now()
                            where payment_snapshots.version < excluded.version""")
                .param("id", s.id())
                .param("tenantId", s.tenantId())
                .param("version", s.version())
                .param("state", serializer.write(s))
                .update();
    }
}
