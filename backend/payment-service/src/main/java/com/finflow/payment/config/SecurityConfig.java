package com.finflow.payment.config;

import com.finflow.security.web.ResourceServerDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Le service valide lui-même chaque jeton, même derrière la Gateway (ADR-004) : il ne fait
 * confiance à aucun en-tête posé en amont.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return ResourceServerDefaults.apply(http).build();
    }
}
