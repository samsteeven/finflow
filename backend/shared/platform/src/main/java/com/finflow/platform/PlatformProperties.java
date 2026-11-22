package com.finflow.platform;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages de résilience d'un appel sortant : coupe-circuit et nombre d'appels simultanés.
 * Pas de nouvel essai ici : les reprises sont celles de la saga (ADR-015), bornées et persistées.
 */
@ConfigurationProperties("finflow.resilience")
public record PlatformProperties(CircuitBreaker circuitBreaker, Bulkhead bulkhead) {

    public PlatformProperties {
        circuitBreaker = circuitBreaker == null ? new CircuitBreaker(null, null, null, null, null) : circuitBreaker;
        bulkhead = bulkhead == null ? new Bulkhead(null, null) : bulkhead;
    }

    public record CircuitBreaker(Integer slidingWindowSize, Integer minimumCalls, Float failureRatePercent,
                                 Duration openDuration, Integer halfOpenCalls) {
        public CircuitBreaker {
            slidingWindowSize = slidingWindowSize == null ? 20 : slidingWindowSize;
            minimumCalls = minimumCalls == null ? 5 : minimumCalls;
            failureRatePercent = failureRatePercent == null ? 50f : failureRatePercent;
            openDuration = openDuration == null ? Duration.ofSeconds(5) : openDuration;
            halfOpenCalls = halfOpenCalls == null ? 2 : halfOpenCalls;
        }
    }

    public record Bulkhead(Integer maxConcurrentCalls, Duration maxWait) {
        public Bulkhead {
            maxConcurrentCalls = maxConcurrentCalls == null ? 25 : maxConcurrentCalls;
            maxWait = maxWait == null ? Duration.ZERO : maxWait;
        }
    }
}
