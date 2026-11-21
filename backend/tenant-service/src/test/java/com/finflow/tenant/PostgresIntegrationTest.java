package com.finflow.tenant;

import com.finflow.testing.TestIssuer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base des tests d'intégration : un vrai PostgreSQL, partagé par toutes les classes, et le faux
 * Keycloak de {@link TestIssuer}. Le service s'y connecte avec le <b>rôle applicatif</b> : un
 * superutilisateur contournerait la RLS et rendrait les tests d'isolation trompeurs.
 */
public abstract class PostgresIntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("finflow_tenant")
            .withInitScript("db/roles-test.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIssuer issuer = TestIssuer.shared();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "finflow_tenant_app");
        registry.add("spring.datasource.password", () -> "app-test");
        registry.add("spring.flyway.user", () -> "finflow_tenant_owner");
        registry.add("spring.flyway.password", () -> "owner-test");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", issuer::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", issuer::jwkSetUri);
    }
}
