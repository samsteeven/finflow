package com.finflow.platform;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Les requêtes d'exploitation (santé, collecte Prometheus toutes les 5 s) ne sont ni tracées ni mesurées
 * comme du trafic : elles noieraient les vraies requêtes dans Jaeger et dans les tableaux de bord.
 */
@Configuration(proxyBeanMethods = false)
class ActuatorObservationFilter {

    static final String HTTP_SERVER = "http.server.requests";

    static boolean keep(String observationName, String path) {
        return !HTTP_SERVER.equals(observationName) || path == null || !path.startsWith("/actuator");
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = Type.SERVLET)
    static class Servlet {
        @Bean
        ObservationPredicate ignoreActuatorServlet() {
            return (name, context) -> keep(name, context instanceof
                    org.springframework.http.server.observation.ServerRequestObservationContext c
                    ? c.getCarrier().getRequestURI() : null);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = Type.REACTIVE)
    static class Reactive {
        @Bean
        ObservationPredicate ignoreActuatorReactive() {
            return (name, context) -> keep(name, context instanceof
                    org.springframework.http.server.reactive.observation.ServerRequestObservationContext c
                    ? c.getCarrier().getURI().getPath() : null);
        }
    }
}
