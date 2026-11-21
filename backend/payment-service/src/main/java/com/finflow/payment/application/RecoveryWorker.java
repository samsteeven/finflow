package com.finflow.payment.application;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Reprend périodiquement les sagas restées en suspens (ADR-015) : le Ledger était indisponible,
 * ou le processus s'est arrêté entre deux étapes. Chaque reprise rejoue la même clé comptable,
 * donc une transaction déjà passée n'est jamais refaite. L'échec d'un paiement n'arrête pas les
 * autres.
 */
@Component
@ConditionalOnProperty(name = "finflow.saga.recovery.enabled", havingValue = "true")
public class RecoveryWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RecoveryWorker.class);

    private final PaymentService payments;
    private final PaymentQueries queries;
    private final Duration interval;
    private final int baseDelaySeconds;
    private final int batchSize;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    RecoveryWorker(PaymentService payments, PaymentQueries queries,
                   @Value("${finflow.saga.recovery.interval:2s}") Duration interval,
                   @Value("${finflow.saga.recovery.base-delay:5s}") Duration baseDelay,
                   @Value("${finflow.saga.recovery.batch-size:20}") int batchSize) {
        this.payments = payments;
        this.queries = queries;
        this.interval = interval;
        this.baseDelaySeconds = (int) Math.max(1, baseDelay.toSeconds());
        this.batchSize = batchSize;
    }

    /** Un tour de reprise ; renvoie le nombre de paiements repris. */
    public int runOnce() {
        List<PaymentQueries.Due> due = queries.dueForRecovery(baseDelaySeconds,
                com.finflow.payment.domain.Payment.MAX_RETRIES, batchSize);
        for (PaymentQueries.Due item : due) {
            try {
                payments.recover(item.tenantId(), item.paymentId());
            } catch (RuntimeException e) {
                log.warn("Reprise du paiement {} en échec : {}", item.paymentId(), e.toString());
            }
        }
        return due.size();
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "saga-recovery");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                log.warn("Tour de reprise des sagas en échec : {}", e.toString());
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
