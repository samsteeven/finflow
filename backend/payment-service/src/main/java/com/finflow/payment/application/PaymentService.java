package com.finflow.payment.application;

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
    private final RiskCheck risk;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentService(PaymentEventStore store, PaymentQueries queries, AccountDirectory accounts,
                          RiskCheck risk, TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.queries = queries;
        this.accounts = accounts;
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
        return new Result(payment, replayed);
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
