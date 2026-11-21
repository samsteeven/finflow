package com.finflow.payment.application;

import com.finflow.payment.domain.IllegalTransitionException;
import com.finflow.payment.domain.Payment;
import com.finflow.payment.domain.PaymentStatus;
import com.finflow.security.TenantContext;
import com.finflow.security.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Création et validation des paiements (ADR-008, ADR-009, ADR-010).
 *
 * <p>Aucune transaction n'englobe un appel réseau : la création, puis la décision de validation
 * sont deux transactions courtes, séparées par la lecture des comptes.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentEventStore store;
    private final PaymentQueries queries;
    private final AccountDirectory accounts;
    private final LedgerGateway ledger;
    private final RiskCheck risk;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentService(PaymentEventStore store, PaymentQueries queries, AccountDirectory accounts,
                          LedgerGateway ledger, RiskCheck risk, TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.queries = queries;
        this.accounts = accounts;
        this.ledger = ledger;
        this.risk = risk;
        this.tx = tx;
        this.clock = clock;
    }

    public record InitiatePayment(UUID sourceAccountId, UUID destinationAccountId, long amountCents,
                                  String currency, String description) {
    }

    public record Result(Payment payment, boolean replayed) {
    }

    public Result initiate(InitiatePayment command, String idempotencyKey, String requestedBy,
                           String correlationId) {
        TenantId tenant = TenantContext.require();
        String hash = fingerprint(command);
        Payment payment;
        boolean replayed;
        try {
            payment = tx.execute(status -> {
                Payment created = Payment.initiate(UUID.randomUUID(), tenant.value(), command.sourceAccountId(),
                        command.destinationAccountId(), command.amountCents(), command.currency(),
                        command.description(), requestedBy, clock.instant());
                queries.recordIdempotencyKey(tenant.value(), idempotencyKey, hash, created.id());
                store.append(created, correlationId);
                return created;
            });
            replayed = false;
        } catch (DuplicateKeyException e) {
            // Même clé déjà utilisée : l'index unique a fait attendre cette requête jusqu'à la
            // validation de la première, on relit donc un paiement complet.
            IdempotencyRecord existing = queries.findIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> e);
            if (!existing.requestHash().equals(hash)) {
                throw new IdempotencyKeyReusedException();
            }
            payment = get(existing.paymentId());
            replayed = true;
        }
        if (payment.status() == PaymentStatus.VALIDATING) {
            payment = validate(payment.id(), correlationId);
        }
        if (payment.status() == PaymentStatus.PROCESSING || payment.status() == PaymentStatus.FAILED) {
            // Première tentative, ou reprise d'un rejeu : la clé comptable est la même (ADR-012).
            payment = settle(payment.id(), correlationId);
        }
        return new Result(payment, replayed);
    }

    /**
     * Fait comptabiliser le paiement par le Ledger, hors transaction, puis enregistre la décision.
     * Une panne ou une réponse perdue laisse le paiement {@code FAILED} : un rejeu de la même
     * demande le reprend, et le Ledger renvoie la transaction déjà passée au lieu d'en refaire une.
     */
    Payment settle(UUID paymentId, String correlationId) {
        Payment current = get(paymentId);
        if (current.status() == PaymentStatus.FAILED) {
            current = resume(paymentId, correlationId);
        }
        if (current.status() != PaymentStatus.PROCESSING) {
            return current;
        }
        LedgerGateway.Outcome outcome = ledger.transfer(paymentId, current.sourceAccountId(),
                current.destinationAccountId(), current.amountCents(), current.description());
        try {
            return tx.execute(status -> {
                Payment latest = get(paymentId);
                if (latest.status() != PaymentStatus.PROCESSING) {
                    return latest;
                }
                Instant now = clock.instant();
                switch (outcome) {
                    case LedgerGateway.Outcome.Posted posted -> latest.complete(posted.transactionId(), now);
                    case LedgerGateway.Outcome.Refused refused -> latest.reject(refused.code(), refused.detail(), now);
                    case LedgerGateway.Outcome.Unavailable down -> {
                        log.warn("Comptabilisation du paiement {} en échec : {}", paymentId, down.reason());
                        latest.fail(down.reason(), now);
                    }
                }
                store.append(latest, correlationId);
                return latest;
            });
        } catch (ConcurrencyConflictException e) {
            return get(paymentId);
        }
    }

    private Payment resume(UUID paymentId, String correlationId) {
        try {
            return tx.execute(status -> {
                Payment latest = get(paymentId);
                if (latest.status() == PaymentStatus.FAILED && latest.retries() < Payment.MAX_RETRIES) {
                    latest.retry(clock.instant());
                    store.append(latest, correlationId);
                }
                return latest;
            });
        } catch (ConcurrencyConflictException e) {
            return get(paymentId);
        }
    }

    /**
     * Reprend la saga d'un paiement resté en suspens, hors de toute requête utilisateur
     * (ADR-015). Elle s'exécute dans l'organisation du paiement ; le Ledger renvoie la
     * transaction déjà passée si la précédente tentative avait réussi sans que Payment le sache.
     */
    public Payment recover(UUID tenantId, UUID paymentId) {
        String correlationId = "reprise-" + paymentId;
        return TenantContext.callAs(TenantId.of(tenantId), () -> {
            Payment current = get(paymentId);
            return switch (current.status()) {
                case PROCESSING, FAILED -> settle(paymentId, correlationId);
                case REFUND_PENDING -> settleRefund(paymentId, correlationId);
                default -> current;
            };
        });
    }

    /**
     * Rembourse un paiement comptabilisé par une contrepassation (ADR-012). Un paiement déjà en
     * cours de remboursement est simplement repris, avec la même clé comptable.
     */
    public Payment refund(UUID paymentId, String reason, String correlationId) {
        Payment current = get(paymentId);
        if (current.status() == PaymentStatus.COMPLETED) {
            try {
                current = tx.execute(status -> {
                    Payment latest = get(paymentId);
                    latest.requestRefund(reason, clock.instant());
                    store.append(latest, correlationId);
                    return latest;
                });
            } catch (ConcurrencyConflictException e) {
                current = get(paymentId);
            }
        }
        if (current.status() != PaymentStatus.REFUND_PENDING) {
            throw new IllegalTransitionException(paymentId, "rembourser", current.status(), null);
        }
        return settleRefund(paymentId, correlationId);
    }

    Payment settleRefund(UUID paymentId, String correlationId) {
        Payment current = get(paymentId);
        if (current.status() != PaymentStatus.REFUND_PENDING) {
            return current;
        }
        LedgerGateway.Outcome outcome = ledger.reverse(current.refundId(), current.ledgerTransactionId(),
                "Remboursement du paiement " + paymentId);
        try {
            return tx.execute(status -> {
                Payment latest = get(paymentId);
                if (latest.status() != PaymentStatus.REFUND_PENDING) {
                    return latest;
                }
                Instant now = clock.instant();
                switch (outcome) {
                    case LedgerGateway.Outcome.Posted posted -> latest.completeRefund(posted.transactionId(), now);
                    case LedgerGateway.Outcome.Refused refused ->
                            latest.rejectRefund(refused.code() + " : " + refused.detail(), now);
                    case LedgerGateway.Outcome.Unavailable down -> {
                        // Rien n'est décidé : le remboursement reste en attente et se rejoue tel quel.
                        log.warn("Remboursement du paiement {} en attente : {}", paymentId, down.reason());
                        return latest;
                    }
                }
                store.append(latest, correlationId);
                return latest;
            });
        } catch (ConcurrencyConflictException e) {
            return get(paymentId);
        }
    }

    /**
     * Lit les comptes hors transaction, puis décide sous le verrou du compte source. Une panne
     * d'Account laisse le paiement en {@code VALIDATING} : il sera repris.
     */
    Payment validate(UUID paymentId, String correlationId) {
        Payment snapshot = get(paymentId);
        Optional<AccountView> source;
        Optional<AccountView> destination;
        try {
            source = accounts.find(snapshot.sourceAccountId());
            destination = accounts.find(snapshot.destinationAccountId());
        } catch (AccountServiceUnavailableException e) {
            log.warn("Validation du paiement {} reportée : {}", paymentId, e.getMessage());
            return snapshot;
        }
        try {
            return tx.execute(status -> {
                queries.lockSourceAccount(snapshot.sourceAccountId());
                Payment current = get(paymentId);
                if (current.status() != PaymentStatus.VALIDATING) {
                    return current;
                }
                Instant now = clock.instant();
                long committed = queries.committedTodayCents(current.sourceAccountId(),
                        now.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant());
                RiskCheck.Decision decision = risk.evaluate(current, source, destination, committed);
                if (decision.accepted()) {
                    current.validate(now);
                } else {
                    current.reject(decision.rule(), decision.detail(), now);
                }
                store.append(current, correlationId);
                return current;
            });
        } catch (ConcurrencyConflictException e) {
            return get(paymentId);
        }
    }

    public Payment get(UUID paymentId) {
        TenantContext.require();
        return store.load(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    public List<StoredEvent> history(UUID paymentId) {
        get(paymentId);
        return store.history(paymentId);
    }

    public List<PaymentSummary> list(PaymentStatus status, int page, int size) {
        TenantContext.require();
        return queries.list(status, page, size);
    }

    public long count(PaymentStatus status) {
        TenantContext.require();
        return queries.count(status);
    }

    /** Empreinte de l'intention : deux requêtes identiques ont la même, quel que soit l'ordre des champs. */
    static String fingerprint(InitiatePayment c) {
        String canonical = String.join("|", String.valueOf(c.sourceAccountId()),
                String.valueOf(c.destinationAccountId()), Long.toString(c.amountCents()), String.valueOf(c.currency()),
                c.description() == null ? "" : c.description());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
