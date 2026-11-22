package com.finflow.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResilienceTest {

    private static Resilience resilience(int minimumCalls, Duration open, int maxConcurrent, SimpleMeterRegistry meters) {
        return new Resilience(new PlatformProperties(
                new PlatformProperties.CircuitBreaker(10, minimumCalls, 50f, open, 1),
                new PlatformProperties.Bulkhead(maxConcurrent, Duration.ZERO)), meters);
    }

    private static void fail(Resilience resilience, int times) {
        for (int i = 0; i < times; i++) {
            assertThatThrownBy(() -> resilience.call("ledger", () -> {
                throw new IllegalStateException("panne");
            })).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void le_circuit_s_ouvre_apres_des_echecs_et_refuse_ensuite_sans_appeler_le_service() {
        Resilience resilience = resilience(4, Duration.ofSeconds(30), 10, new SimpleMeterRegistry());
        fail(resilience, 4);
        AtomicInteger appels = new AtomicInteger();

        assertThatThrownBy(() -> resilience.call("ledger", appels::incrementAndGet))
                .satisfies(e -> assertThat(Resilience.isRejection(e)).isTrue())
                .isInstanceOf(CallNotPermittedException.class);

        assertThat(resilience.state("ledger")).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(appels).hasValue(0);
    }

    @Test
    void en_dessous_du_minimum_d_appels_le_circuit_reste_ferme() {
        Resilience resilience = resilience(5, Duration.ofSeconds(30), 10, new SimpleMeterRegistry());

        fail(resilience, 4);

        assertThat(resilience.state("ledger")).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void une_reponse_en_valeur_ne_compte_jamais_comme_un_echec() {
        Resilience resilience = resilience(4, Duration.ofSeconds(30), 10, new SimpleMeterRegistry());

        for (int i = 0; i < 20; i++) {
            assertThat(resilience.call("ledger", () -> "refus métier")).isEqualTo("refus métier");
        }

        assertThat(resilience.state("ledger")).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void apres_la_duree_d_ouverture_un_appel_reussi_referme_le_circuit() throws Exception {
        Resilience resilience = resilience(4, Duration.ofMillis(200), 10, new SimpleMeterRegistry());
        fail(resilience, 4);
        Thread.sleep(400);

        assertThat(resilience.call("ledger", () -> "ok")).isEqualTo("ok");

        assertThat(resilience.state("ledger")).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void les_circuits_sont_independants_par_service() {
        Resilience resilience = resilience(4, Duration.ofSeconds(30), 10, new SimpleMeterRegistry());
        fail(resilience, 4);

        assertThat(resilience.call("account", () -> "ok")).isEqualTo("ok");
        assertThat(resilience.state("account")).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void la_cloison_refuse_les_appels_au_dela_de_la_limite_sans_les_attendre() throws Exception {
        Resilience resilience = resilience(100, Duration.ofSeconds(30), 2, new SimpleMeterRegistry());
        CountDownLatch occupes = new CountDownLatch(2);
        CountDownLatch liberer = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> resilience.call("ledger", () -> {
                    occupes.countDown();
                    try {
                        liberer.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "ok";
                }));
            }
            assertThat(occupes.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> resilience.call("ledger", () -> "trop"))
                    .satisfies(e -> assertThat(Resilience.isRejection(e)).isTrue());
        } finally {
            liberer.countDown();
            pool.shutdown();
        }
    }

    @Test
    void les_etats_et_les_appels_sont_publies_comme_metriques() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Resilience resilience = resilience(4, Duration.ofSeconds(30), 10, meters);
        fail(resilience, 4);

        assertThat(meters.find("resilience4j.circuitbreaker.state").tag("name", "ledger").tag("state", "open")
                .gauge().value()).isEqualTo(1.0);
        assertThat(meters.find("resilience4j.circuitbreaker.calls").tag("name", "ledger").tag("kind", "failed")
                .timer().count()).isEqualTo(4);
    }

    @Test
    void les_erreurs_ordinaires_ne_sont_pas_des_refus() {
        assertThat(Resilience.isRejection(new IllegalStateException())).isFalse();
    }
}
