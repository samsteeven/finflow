package com.finflow.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.finflow.testing.TestIssuer;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * La Gateway devant un faux tenant-service qui enregistre ce qu'il reçoit : on vérifie ce qui
 * passe et ce qui ne passe pas, avec de vrais jetons signés.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {

    private static final TestIssuer ISSUER = TestIssuer.shared();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final Map<String, List<String>> LAST_HEADERS = new ConcurrentHashMap<>();
    private static final HttpServer DOWNSTREAM = startDownstream();

    @LocalServerPort int port;

    WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwkSetUri);
        String downstream = "http://localhost:" + DOWNSTREAM.getAddress().getPort();
        registry.add("TENANT_SERVICE_URL", () -> downstream);
        registry.add("ACCOUNT_SERVICE_URL", () -> downstream);
        registry.add("PAYMENT_SERVICE_URL", () -> downstream);
    }

    @BeforeEach
    void setUp() {
        CALLS.set(0);
        LAST_HEADERS.clear();
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    @AfterAll
    static void stop() {
        DOWNSTREAM.stop(0);
    }

    @Test
    void une_requete_sans_jeton_n_atteint_jamais_le_service() {
        client.get().uri("/api/v1/tenants/current").exchange().expectStatus().isUnauthorized();

        assertThat(CALLS).hasValue(0);
    }

    @Test
    void un_jeton_forge_ou_destine_a_un_autre_public_est_refuse() {
        for (String token : List.of(
                ISSUER.token().signedByUnknownKey().build(),
                ISSUER.token().audience("autre-api").build(),
                ISSUER.token().validFor(Duration.ofMinutes(-5)).build())) {
            client.get().uri("/api/v1/tenants/current").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange().expectStatus().isUnauthorized();
        }
        assertThat(CALLS).hasValue(0);
    }

    @Test
    void une_requete_authentifiee_est_routee_avec_son_jeton() {
        String token = ISSUER.token().tenant(UUID.randomUUID()).roles("tenant-member").build();

        client.get().uri("/api/v1/tenants/current").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo("/api/v1/tenants/current");

        assertThat(CALLS).hasValue(1);
        assertThat(header("Authorization")).isEqualTo("Bearer " + token);
    }

    @Test
    void comptes_et_paiements_sont_routes_avec_la_cle_d_idempotence() {
        String token = ISSUER.token().tenant(UUID.randomUUID()).build();

        client.get().uri("/api/v1/accounts?page=0").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo("/api/v1/accounts");
        client.post().uri("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", "cle-de-test-123")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo("/api/v1/payments");

        assertThat(CALLS).hasValue(2);
        assertThat(header("Idempotency-Key")).isEqualTo("cle-de-test-123");
    }

    @Test
    void les_en_tetes_d_identite_forges_par_le_client_sont_retires() {
        String token = ISSUER.token().tenant(UUID.randomUUID()).build();

        client.get().uri("/api/v1/tenants/current")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Tenant-Id", UUID.randomUUID().toString())
                .header("X-User-Id", "admin")
                .header(HttpHeaders.COOKIE, "session=volee")
                .exchange().expectStatus().isOk();

        assertThat(LAST_HEADERS).doesNotContainKeys("x-tenant-id", "x-user-id", "cookie");
    }

    @Test
    void une_route_inconnue_ne_donne_rien_meme_authentifie() {
        client.get().uri("/api/v1/ledger/transactions/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ISSUER.token().build())
                .exchange().expectStatus().isNotFound();
        assertThat(CALLS).hasValue(0);
    }

    @Test
    void la_sante_est_publique() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void le_front_local_est_autorise_par_cors_mais_pas_une_autre_origine() {
        client.options().uri("/api/v1/tenants/current")
                .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200");

        client.options().uri("/api/v1/tenants/current")
                .header(HttpHeaders.ORIGIN, "https://pirate.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange().expectStatus().isForbidden();
    }

    private static String header(String name) {
        List<String> values = LAST_HEADERS.get(name.toLowerCase());
        return values == null ? null : values.getFirst();
    }

    private static HttpServer startDownstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                CALLS.incrementAndGet();
                exchange.getRequestHeaders().forEach((k, v) -> LAST_HEADERS.put(k.toLowerCase(), v));
                byte[] body = ("{\"path\":\"" + exchange.getRequestURI().getPath() + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
