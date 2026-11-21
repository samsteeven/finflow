package com.finflow.payment;

import com.finflow.payment.application.ServiceTokenProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Remplace l'appel à Keycloak par un jeton fixe : on teste la reprise, pas le serveur d'identité. */
@TestConfiguration
class TestServiceToken {

    static final String VALUE = "jeton-de-service-de-test";

    @Bean
    @Primary
    ServiceTokenProvider serviceTokens() {
        return () -> VALUE;
    }
}
