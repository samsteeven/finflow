package com.finflow.payment.domain;

import com.finflow.payment.domain.PaymentEvent.PaymentCompleted;
import com.finflow.payment.domain.PaymentEvent.PaymentCreated;
import com.finflow.payment.domain.PaymentEvent.PaymentFailed;
import com.finflow.payment.domain.PaymentEvent.PaymentRejected;
import com.finflow.payment.domain.PaymentEvent.PaymentRetried;
import com.finflow.payment.domain.PaymentEvent.PaymentValidated;
import com.finflow.payment.domain.PaymentEvent.PaymentValidationStarted;
import com.finflow.payment.domain.PaymentEvent.RefundCompleted;
import com.finflow.payment.domain.PaymentEvent.RefundRejected;
import com.finflow.payment.domain.PaymentEvent.RefundRequested;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Agrégat Paiement, reconstruit à partir de ses événements (ADR-008).
 *
 * <p>Chaque décision vérifie la machine à états puis lève un événement ; l'état ne change que
 * dans {@link #apply}, qui sert aussi au rejeu. Les événements levés restent en attente jusqu'à
 * leur écriture dans l'event store.
 */
public final class Payment {

    /** Nombre maximal de reprises après un échec technique : on ne réessaie pas indéfiniment. */
    public static final int MAX_RETRIES = 3;
    public static final String CURRENCY = "EUR";

    private UUID id;
    private UUID tenantId;
    private UUID sourceAccountId;
    private UUID destinationAccountId;
    private long amountCents;
    private String currency;
    private String description;
    private String requestedBy;
    private PaymentStatus status;
    private Rejection rejection;
    private String failureReason;
    private int retries;
    private UUID ledgerTransactionId;
    private UUID refundLedgerTransactionId;
    private UUID refundId;
    private String refundRejection;
    private Instant createdAt;
    private Instant updatedAt;
    private int version;

    private final List<PaymentEvent> pending = new ArrayList<>();

    private Payment() {
    }

    public static Payment initiate(UUID id, UUID tenantId, UUID sourceAccountId, UUID destinationAccountId,
                                   long amountCents, String currency, String description, String requestedBy,
                                   Instant now) {
        Objects.requireNonNull(sourceAccountId, "sourceAccountId");
        Objects.requireNonNull(destinationAccountId, "destinationAccountId");
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new InvalidPaymentException("Le compte source et le compte destinataire doivent être différents");
        }
        if (amountCents <= 0) {
            throw new InvalidPaymentException("Le montant doit être strictement positif");
        }
        if (!CURRENCY.equals(currency)) {
            throw new InvalidPaymentException("Seul l'euro est pris en charge");
        }
        Payment payment = new Payment();
        payment.raise(new PaymentCreated(id, tenantId, sourceAccountId, destinationAccountId, amountCents, currency,
                description, requestedBy, now));
        payment.raise(new PaymentValidationStarted(id, now));
        return payment;
    }

    /** Rejoue l'historique complet. */
    public static Payment replay(List<PaymentEvent> history) {
        return replay(null, history);
    }

    /** Repart d'un snapshot (ou de rien) et rejoue les événements suivants. */
    public static Payment replay(PaymentState snapshot, List<PaymentEvent> history) {
        Payment payment = snapshot == null ? new Payment() : fromState(snapshot);
        history.forEach(payment::apply);
        if (payment.id == null) {
            throw new IllegalStateException("Historique vide : aucun paiement à reconstruire");
        }
        return payment;
    }

    public void validate(Instant now) {
        require(PaymentStatus.VALIDATING, "valider");
        raise(new PaymentValidated(id, now));
    }

    /** Refus métier : à la validation (limites, comptes) ou à la comptabilisation (fonds manquants). */
    public void reject(String rule, String detail, Instant now) {
        if (status != PaymentStatus.VALIDATING && status != PaymentStatus.PROCESSING) {
            throw new IllegalTransitionException(id, "rejeter", status, null);
        }
        raise(new PaymentRejected(id, rule, detail, now));
    }

    public void complete(UUID ledgerTransactionId, Instant now) {
        require(PaymentStatus.PROCESSING, "terminer");
        raise(new PaymentCompleted(id, Objects.requireNonNull(ledgerTransactionId), now));
    }

    public void fail(String reason, Instant now) {
        require(PaymentStatus.PROCESSING, "mettre en échec");
        raise(new PaymentFailed(id, reason, now));
    }

    public void retry(Instant now) {
        require(PaymentStatus.FAILED, "reprendre");
        if (retries >= MAX_RETRIES) {
            throw new IllegalTransitionException(id, "reprendre", status,
                    "nombre maximal de reprises atteint (" + MAX_RETRIES + ")");
        }
        raise(new PaymentRetried(id, retries + 1, now));
    }

    public void requestRefund(String reason, Instant now) {
        require(PaymentStatus.COMPLETED, "rembourser");
        raise(new RefundRequested(id, UUID.randomUUID(), reason, now));
    }

    public void completeRefund(UUID ledgerTransactionId, Instant now) {
        require(PaymentStatus.REFUND_PENDING, "terminer le remboursement de");
        raise(new RefundCompleted(id, Objects.requireNonNull(ledgerTransactionId), now));
    }

    /** Le Ledger refuse la contrepassation : le paiement reste comptabilisé, donc terminé. */
    public void rejectRefund(String reason, Instant now) {
        require(PaymentStatus.REFUND_PENDING, "refuser le remboursement de");
        raise(new RefundRejected(id, reason, now));
    }

    private void require(PaymentStatus expected, String action) {
        if (status != expected) {
            throw new IllegalTransitionException(id, action, status, null);
        }
    }

    private void raise(PaymentEvent event) {
        apply(event);
        pending.add(event);
    }

    private void apply(PaymentEvent event) {
        if (event instanceof PaymentCreated ? id != null : !event.paymentId().equals(id)) {
            throw new IllegalStateException("Événement incohérent avec le paiement " + id + " : " + event);
        }
        switch (event) {
            case PaymentCreated e -> {
                id = e.paymentId();
                tenantId = e.tenantId();
                sourceAccountId = e.sourceAccountId();
                destinationAccountId = e.destinationAccountId();
                amountCents = e.amountCents();
                currency = e.currency();
                description = e.description();
                requestedBy = e.requestedBy();
                createdAt = e.occurredAt();
                status = PaymentStatus.CREATED;
            }
            case PaymentValidationStarted e -> status = PaymentStatus.VALIDATING;
            case PaymentValidated e -> status = PaymentStatus.PROCESSING;
            case PaymentRejected e -> {
                status = PaymentStatus.REJECTED;
                rejection = new Rejection(e.rule(), e.detail());
            }
            case PaymentCompleted e -> {
                status = PaymentStatus.COMPLETED;
                ledgerTransactionId = e.ledgerTransactionId();
                failureReason = null;
            }
            case PaymentFailed e -> {
                status = PaymentStatus.FAILED;
                failureReason = e.reason();
            }
            case PaymentRetried e -> {
                status = PaymentStatus.PROCESSING;
                retries = e.attempt();
            }
            case RefundRequested e -> {
                status = PaymentStatus.REFUND_PENDING;
                refundId = e.refundId();
            }
            case RefundRejected e -> {
                status = PaymentStatus.COMPLETED;
                refundId = null;
                refundRejection = e.reason();
            }
            case RefundCompleted e -> {
                status = PaymentStatus.REFUNDED;
                refundLedgerTransactionId = e.ledgerTransactionId();
            }
        }
        updatedAt = event.occurredAt();
        version++;
    }

    /** Événements levés depuis le dernier chargement, dans l'ordre. */
    public List<PaymentEvent> pendingEvents() {
        return List.copyOf(pending);
    }

    /** Version connue avant les événements en attente : celle que l'event store doit trouver. */
    public int expectedVersion() {
        return version - pending.size();
    }

    public void markCommitted() {
        pending.clear();
    }

    public PaymentState state() {
        return new PaymentState(id, tenantId, sourceAccountId, destinationAccountId, amountCents, currency,
                description, requestedBy, status, rejection, failureReason, retries, ledgerTransactionId,
                refundLedgerTransactionId, refundId, refundRejection, createdAt, updatedAt, version);
    }

    private static Payment fromState(PaymentState s) {
        Payment p = new Payment();
        p.id = s.id();
        p.tenantId = s.tenantId();
        p.sourceAccountId = s.sourceAccountId();
        p.destinationAccountId = s.destinationAccountId();
        p.amountCents = s.amountCents();
        p.currency = s.currency();
        p.description = s.description();
        p.requestedBy = s.requestedBy();
        p.status = s.status();
        p.rejection = s.rejection();
        p.failureReason = s.failureReason();
        p.retries = s.retries();
        p.ledgerTransactionId = s.ledgerTransactionId();
        p.refundLedgerTransactionId = s.refundLedgerTransactionId();
        p.refundId = s.refundId();
        p.refundRejection = s.refundRejection();
        p.createdAt = s.createdAt();
        p.updatedAt = s.updatedAt();
        p.version = s.version();
        return p;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID sourceAccountId() {
        return sourceAccountId;
    }

    public UUID destinationAccountId() {
        return destinationAccountId;
    }

    public long amountCents() {
        return amountCents;
    }

    public String currency() {
        return currency;
    }

    public String description() {
        return description;
    }

    public UUID ledgerTransactionId() {
        return ledgerTransactionId;
    }

    /** Identifiant de la demande de remboursement en cours : la clé d'idempotence côté Ledger. */
    public UUID refundId() {
        return refundId;
    }

    public int retries() {
        return retries;
    }

    public PaymentStatus status() {
        return status;
    }

    public int version() {
        return version;
    }

    public record Rejection(String rule, String detail) {
    }
}
