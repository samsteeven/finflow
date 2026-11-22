package com.finflow.gateway;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Limite le débit par organisation (ADR-017) : un client bruyant ne doit pas priver les autres du service.
 * La clé est l'organisation du jeton validé, jamais un en-tête ; sans organisation (compte technique),
 * c'est le sujet du jeton. Au-delà du quota : 429 et {@code Retry-After}, sans solliciter le service.
 */
@Component
class TenantRateLimitFilter implements GlobalFilter, Ordered {

    private final RateLimiterRegistry limiters;
    private final ConcurrentMap<String, RateLimiter> byKey = new ConcurrentHashMap<>();
    private final RateLimiterConfig config;
    private final MeterRegistry meters;
    private final boolean enabled;
    private final long retryAfterSeconds;

    TenantRateLimitFilter(
            @Value("${finflow.gateway.rate-limit.enabled:true}") boolean enabled,
            @Value("${finflow.gateway.rate-limit.requests-per-period:300}") int requests,
            @Value("${finflow.gateway.rate-limit.period:1m}") Duration period,
            MeterRegistry meters) {
        this.enabled = enabled;
        this.meters = meters;
        this.retryAfterSeconds = Math.max(1, period.toSeconds());
        this.config = RateLimiterConfig.custom()
                .limitForPeriod(requests)
                .limitRefreshPeriod(period)
                .timeoutDuration(Duration.ZERO)
                .build();
        this.limiters = RateLimiterRegistry.of(config);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(auth -> key(((JwtAuthenticationToken) auth).getToken()))
                .defaultIfEmpty("anonyme")
                .flatMap(key -> {
                    RateLimiter limiter = byKey.computeIfAbsent(key, k -> limiters.rateLimiter(k, config));
                    if (limiter.acquirePermission()) {
                        return chain.filter(exchange);
                    }
                    meters.counter("finflow.gateway.rate_limited").increment();
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    exchange.getResponse().getHeaders().set("Retry-After", Long.toString(retryAfterSeconds));
                    return exchange.getResponse().setComplete();
                });
    }

    private static String key(Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant_id");
        return tenant != null ? "tenant:" + tenant : "sujet:" + jwt.getSubject();
    }

    @Override
    public int getOrder() {
        return -50;
    }
}
