package com.finflow.testing;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base PostgreSQL d'un service pour les tests d'intégration, avec le même découpage qu'en local
 * et sur AWS (ADR-005) : un rôle propriétaire pour Flyway, un rôle applicatif sans privilège
 * pour le service. Un superutilisateur contournerait la RLS et rendrait les tests trompeurs.
 *
 * <p>Un conteneur par service et par JVM, partagé par toutes les classes de test.
 */
public final class ServiceDatabase {

    private static final Map<String, ServiceDatabase> STARTED = new ConcurrentHashMap<>();

    private final PostgreSQLContainer container;
    private final String owner;
    private final String app;

    private ServiceDatabase(String service) {
        this.owner = "finflow_" + service + "_owner";
        this.app = "finflow_" + service + "_app";
        this.container = new PostgreSQLContainer("postgres:18-alpine").withDatabaseName("finflow_" + service);
        container.start();
        try (Connection c = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE ROLE " + owner + " LOGIN PASSWORD 'owner-test'");
            st.execute("CREATE ROLE " + app + " LOGIN PASSWORD 'app-test'");
            st.execute("ALTER SCHEMA public OWNER TO " + owner);
            st.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        } catch (SQLException e) {
            throw new IllegalStateException("Préparation de la base de test impossible", e);
        }
    }

    /** {@code service} : tenant, account, payment ou ledger. */
    public static ServiceDatabase of(String service) {
        return STARTED.computeIfAbsent(service, ServiceDatabase::new);
    }

    public String jdbcUrl() {
        return container.getJdbcUrl();
    }

    public String appUser() {
        return app;
    }

    /** Connexion superutilisateur, réservée à la préparation ou à l'inspection dans les tests. */
    public Connection superuserConnection() throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /** Base, rôles et faux Keycloak branchés sur la configuration de production du service. */
    public void register(DynamicPropertyRegistry registry) {
        TestIssuer issuer = TestIssuer.shared();
        registry.add("spring.datasource.url", this::jdbcUrl);
        registry.add("spring.datasource.username", () -> app);
        registry.add("spring.datasource.password", () -> "app-test");
        registry.add("spring.flyway.user", () -> owner);
        registry.add("spring.flyway.password", () -> "owner-test");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", issuer::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", issuer::jwkSetUri);
    }
}
