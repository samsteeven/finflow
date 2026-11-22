package com.finflow.security.web;

import com.finflow.security.KeycloakJwtAuthenticationConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;

/**
 * Règles communes à tous les services servlet : santé publique, tout le reste authentifié par
 * un jeton Keycloak revalidé ici (ADR-004), sans session ni cookie.
 */
public final class ResourceServerDefaults {

    private ResourceServerDefaults() {
    }

    public static HttpSecurity apply(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Métriques : seul le compte technique de Prometheus les lit (rôle metrics).
                        .requestMatchers("/actuator/prometheus").hasRole("METRICS")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(new KeycloakJwtAuthenticationConverter())))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // API sans cookie : le jeton porteur n'est pas envoyé automatiquement par le navigateur.
                .csrf(csrf -> csrf.disable());
    }
}
