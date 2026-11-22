package com.finflow.payment.infrastructure;

import com.finflow.payment.application.ConcurrencyConflictException;
import com.finflow.payment.application.PaymentEventStore;
import com.finflow.payment.application.StoredEvent;
import com.finflow.payment.domain.Payment;
import com.finflow.payment.domain.PaymentEvent;
import com.finflow.payment.domain.PaymentState;
import com.finflow.events.PaymentChanged;
import com.finflow.messaging.Envelopes;
import com.finflow.messaging.Outbox;
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
    static final String TOPIC = "payment.events";

    private final JdbcClient jdbc;
    private final EventSerializer serializer;
    private final Outbox outbox;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    JdbcPaymentEventStore(JdbcClient jdbc, EventSerializer serializer, Outbox outbox,
                          io.micrometer.core.instrument.MeterRegistry meters) {
        this.jdbc = jdbc;
        this.serializer = serializer;
        this.outbox = outbox;
        this.meters = meters;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(Payment payment, String correlationId) {
        List<PaymentEvent> events = payment.pendingEvents();
        if (events.isEmpty()) {
            return;
        }
        int version = payment.expectedVersion();
        // La cause du premier événement d'un lot est le dernier événement déjà écrit : la chaîne de
        // causalité ne s'interrompt pas d'une transaction à l'autre.
        UUID causation = version == 0 ? null : jdbc.sql(
                        "select event_id from payment_events where payment_id = :id and version = :version")
                .param("id", payment.id()).param("version", version).query(UUID.class).optional().orElse(null);
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
                publish(payment, event, version, eventId, correlationId, causation);
                causation = eventId;
            }
        } catch (DuplicateKeyException e) {
            throw new ConcurrencyConflictException(payment.id(), payment.expectedVersion());
        }
        project(payment.state());
        watch(payment.state());
        countAfterCommit(events);
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

    /**
     * Écrit l'événement dans l'outbox, dans la même transaction que l'event store (ADR-013). Le
     * statut publié est celui qui suit l'événement ; le reste décrit le paiement, qui ne change pas.
     */
    private void publish(Payment payment, PaymentEvent event, int version, UUID eventId, String correlationId,
                         UUID causation) {
        PaymentState state = payment.state();
        PaymentChanged payload = PaymentChanged.newBuilder()
                .setStatus(statusAfter(event))
                .setSourceAccountId(state.sourceAccountId().toString())
                .setDestinationAccountId(state.destinationAccountId().toString())
                .setAmountCents(state.amountCents())
                .setCurrency(state.currency())
                .setLedgerTransactionId(switch (event) {
                    case PaymentEvent.PaymentCompleted completed -> completed.ledgerTransactionId().toString();
                    case PaymentEvent.RefundCompleted completed -> completed.ledgerTransactionId().toString();
                    default -> null;
                })
                .setReason(switch (event) {
                    case PaymentEvent.PaymentRejected rejected -> rejected.rule();
                    case PaymentEvent.PaymentFailed failed -> failed.reason();
                    case PaymentEvent.RefundRequested requested -> requested.reason();
                    case PaymentEvent.RefundRejected rejected -> rejected.reason();
                    default -> null;
                })
                .build();
        outbox.append(TOPIC, Envelopes.of(eventId, "payment-service", event.type(), payment.id(), version,
                payment.tenantId(), correlationId, causation == null ? null : causation.toString(),
                event.occurredAt(), payload));
    }

    private static String statusAfter(PaymentEvent event) {
        return switch (event) {
            case PaymentEvent.PaymentCreated e -> "CREATED";
            case PaymentEvent.PaymentValidationStarted e -> "VALIDATING";
            case PaymentEvent.PaymentValidated e -> "PROCESSING";
            case PaymentEvent.PaymentRejected e -> "REJECTED";
            case PaymentEvent.PaymentCompleted e -> "COMPLETED";
            case PaymentEvent.PaymentFailed e -> "FAILED";
            case PaymentEvent.PaymentRetried e -> "PROCESSING";
            case PaymentEvent.RefundRequested e -> "REFUND_PENDING";
            case PaymentEvent.RefundRejected e -> "COMPLETED";
            case PaymentEvent.RefundCompleted e -> "REFUNDED";
        };
    }

    /**
     * Tient à jour la liste des sagas en suspens (ADR-015) : un paiement y entre quand il attend
     * le Ledger, et en sort dès qu'il est terminé.
     */
    /** Compte les événements écrits, mais seulement si la transaction aboutit : un retour arrière ne compte pas. */
    private void countAfterCommit(List<PaymentEvent> events) {
        List<String> types = events.stream().map(PaymentEvent::type).toList();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        types.forEach(type -> meters.counter("finflow.payment.events", "type", type).increment());
                    }
                });
    }

    private void watch(PaymentState s) {
        boolean pending = s.status() == com.finflow.payment.domain.PaymentStatus.PROCESSING
                || s.status() == com.finflow.payment.domain.PaymentStatus.FAILED
                || s.status() == com.finflow.payment.domain.PaymentStatus.REFUND_PENDING;
        if (!pending) {
            jdbc.sql("delete from saga_watch where payment_id = :id").param("id", s.id()).update();
            return;
        }
        jdbc.sql("""
                        insert into saga_watch (payment_id, tenant_id, status, retries, updated_at)
                        values (:id, :tenantId, :status, :retries, :updatedAt)
                        on conflict (payment_id) do update
                           set status = excluded.status, retries = excluded.retries,
                               updated_at = excluded.updated_at""")
                .param("id", s.id())
                .param("tenantId", s.tenantId())
                .param("status", s.status().name())
                .param("retries", s.retries())
                .param("updatedAt", Timestamp.from(s.updatedAt()))
                .update();
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
