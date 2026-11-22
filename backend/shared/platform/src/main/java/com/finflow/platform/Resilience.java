package com.finflow.platform;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;

/**
 * Protège un appel sortant : un coupe-circuit qui refuse vite quand le service appelé est malade, et
 * une cloison qui borne les appels simultanés pour qu'un service lent n'épuise pas les fils d'exécution.
 *
 * <p>Seules les exceptions comptent comme échecs ; un refus métier renvoyé en valeur ne fait pas
 * ouvrir le circuit. L'appelant décide quoi faire d'un refus de cette classe ({@link #isRejection}).
 */
public final class Resilience {

    private final CircuitBreakerRegistry circuitBreakers;
    private final BulkheadRegistry bulkheads;

    Resilience(PlatformProperties properties, MeterRegistry meters) {
        PlatformProperties.CircuitBreaker cb = properties.circuitBreaker();
        this.circuitBreakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cb.slidingWindowSize())
                .minimumNumberOfCalls(cb.minimumCalls())
                .failureRateThreshold(cb.failureRatePercent())
                .waitDurationInOpenState(cb.openDuration())
                .permittedNumberOfCallsInHalfOpenState(cb.halfOpenCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build());
        PlatformProperties.Bulkhead bh = properties.bulkhead();
        this.bulkheads = BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(bh.maxConcurrentCalls())
                .maxWaitDuration(bh.maxWait())
                .build());
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakers).bindTo(meters);
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(meters);
    }

    /** Exécute l'appel sous coupe-circuit et cloison nommés. Une exception levée par l'appel est un échec. */
    public <T> T call(String name, Supplier<T> call) {
        CircuitBreaker circuitBreaker = circuitBreakers.circuitBreaker(name);
        Bulkhead bulkhead = bulkheads.bulkhead(name);
        return Bulkhead.decorateSupplier(bulkhead, CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
    }

    /** Vrai si l'appel a été refusé sans être tenté : circuit ouvert ou trop d'appels simultanés. */
    public static boolean isRejection(Throwable error) {
        return error instanceof CallNotPermittedException || error instanceof BulkheadFullException;
    }

    public CircuitBreaker.State state(String name) {
        return circuitBreakers.circuitBreaker(name).getState();
    }
}
