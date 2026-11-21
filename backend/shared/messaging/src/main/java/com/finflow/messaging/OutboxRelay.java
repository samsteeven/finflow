package com.finflow.messaging;

import com.finflow.events.EventEnvelope;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publie les événements de la table outbox vers Kafka, dans l'ordre d'insertion (ADR-013).
 *
 * <p>La livraison est « au moins une fois » : si le processus s'arrête entre l'envoi et la
 * validation de la marque {@code published_at}, l'événement repart au tour suivant ; les
 * consommateurs sont idempotents. Un verrou consultatif PostgreSQL ne laisse travailler qu'une
 * instance à la fois, ce qui préserve l'ordre par agrégat. Au premier échec d'envoi, le lot
 * s'arrête : un événement n'en dépasse jamais un plus ancien.
 */
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long LOCK_KEY = 0x46494e464c4f57L; // « FINFLOW »

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, EventEnvelope> kafka;
    private final MessagingProperties.Relay settings;
    private final Runnable ensureTopics;
    private boolean topicsReady;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, EventEnvelope> kafka,
                       MessagingProperties.Relay settings, Runnable ensureTopics) {
        this.ensureTopics = ensureTopics;
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.settings = settings;
    }

    private record Row(long id, String topic, String key, String payload) {
    }

    /** Publie un lot ; renvoie le nombre d'événements publiés. */
    public int publishPending() {
        Integer published = tx.execute(status -> {
            Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(locked)) {
                return 0;
            }
            List<Row> batch = jdbc.query("""
                    select id, topic, message_key, payload from outbox
                     where published_at is null order by id limit ?
                    """, (rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                    settings.batchSize());
            if (!batch.isEmpty() && !topicsReady) {
                try {
                    // Sans les topics (le broker n'en crée aucun tout seul), tout envoi échouerait.
                    ensureTopics.run();
                    topicsReady = true;
                } catch (RuntimeException e) {
                    log.warn("Topics Kafka indisponibles : {}", e.toString());
                    return 0;
                }
            }
            int done = 0;
            for (Row row : batch) {
                try {
                    kafka.send(row.topic(), row.key(), AvroJson.read(row.payload()))
                            .get(settings.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("Publication de l'événement outbox {} impossible : {}", row.id(), e.toString());
                    jdbc.update("update outbox set attempts = attempts + 1, last_error = ? where id = ?",
                            truncate(e.toString()), row.id());
                    break;
                }
                jdbc.update("update outbox set published_at = now() where id = ?", row.id());
                done++;
            }
            return done;
        });
        return published == null ? 0 : published;
    }

    /** Supprime les événements publiés depuis plus longtemps que la durée de conservation. */
    public int purge() {
        return jdbc.update("delete from outbox where published_at < now() - make_interval(secs => ?)",
                (double) settings.retention().toSeconds());
    }

    public long pending() {
        Long count = jdbc.queryForObject("select count(*) from outbox where published_at is null", Long.class);
        return count == null ? 0 : count;
    }

    private static String truncate(String text) {
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
        long period = Math.max(50, settings.interval().toMillis());
        executor.scheduleWithFixedDelay(this::tick, period, period, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(this::purgeQuietly, 1, 60, TimeUnit.MINUTES);
        running = true;
    }

    private void tick() {
        try {
            while (running && publishPending() >= settings.batchSize()) {
                // Un lot plein : il en reste probablement.
            }
        } catch (RuntimeException e) {
            log.warn("Tour du relais outbox en échec : {}", e.toString());
        }
    }

    private void purgeQuietly() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.warn("Purge de l'outbox en échec : {}", e.toString());
        }
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
