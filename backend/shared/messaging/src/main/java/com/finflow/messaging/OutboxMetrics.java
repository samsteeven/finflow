package com.finflow.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Jauges de l'outbox, lues à chaque collecte : événements en attente de publication et âge du plus
 * ancien. Un broker arrêté se voit ici avant de se voir ailleurs. Un service sans outbox (Account
 * ne fait que consommer) n'expose rien.
 */
public class OutboxMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final JdbcTemplate jdbc;

    public OutboxMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (!hasOutbox()) {
            return;
        }
        Gauge.builder("finflow.outbox.pending", this, m -> m.read("select count(*) from outbox where published_at is null"))
                .description("Événements écrits mais pas encore publiés sur Kafka")
                .register(registry);
        Gauge.builder("finflow.outbox.oldest.age.seconds", this, m -> m.read("""
                        select coalesce(extract(epoch from now() - min(created_at)), 0)
                          from outbox where published_at is null"""))
                .description("Âge du plus ancien événement en attente de publication")
                .baseUnit("seconds")
                .register(registry);
    }

    private boolean hasOutbox() {
        try {
            return jdbc.queryForObject("select to_regclass('outbox') is not null", Boolean.class) == Boolean.TRUE;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private double read(String sql) {
        try {
            Double value = jdbc.queryForObject(sql, Double.class);
            return value == null ? 0 : value;
        } catch (RuntimeException e) {
            log.debug("Jauge de l'outbox illisible : {}", e.toString());
            return Double.NaN; // pas d'outbox ou base indisponible : la collecte n'échoue pas
        }
    }
}
