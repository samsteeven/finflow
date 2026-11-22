package com.finflow.payment.infrastructure;

import com.finflow.payment.domain.Payment;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Jauges de la saga lues à chaque collecte : paiements en suspens par statut, et paiements qui ont
 * épuisé leurs reprises et attendent une décision humaine (ADR-015). Ce dernier chiffre doit rester à zéro.
 */
@Component
class SagaMetrics implements MeterBinder {

    private final JdbcClient jdbc;

    SagaMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String status : new String[] {"PROCESSING", "FAILED", "REFUND_PENDING"}) {
            Gauge.builder("finflow.saga.pending", this, m -> m.count("status = '" + status + "'"))
                    .description("Paiements en suspens, repris par le worker")
                    .tag("status", status).register(registry);
        }
        Gauge.builder("finflow.saga.stuck", this,
                        m -> m.count("status = 'FAILED' and retries >= " + Payment.MAX_RETRIES))
                .description("Paiements dont les reprises sont épuisées")
                .register(registry);
    }

    private double count(String condition) {
        try {
            Long count = jdbc.sql("select count(*) from saga_watch where " + condition).query(Long.class).single();
            return count;
        } catch (RuntimeException e) {
            return Double.NaN; // base indisponible : la collecte n'échoue pas
        }
    }
}
