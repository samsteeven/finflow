package com.finflow.tenant;

import com.finflow.testing.ServiceDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Base des tests d'intégration : vrai PostgreSQL, rôle applicatif, faux Keycloak. */
public abstract class PostgresIntegrationTest {

    static final ServiceDatabase DATABASE = ServiceDatabase.of("tenant");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DATABASE.register(registry);
    }
}
