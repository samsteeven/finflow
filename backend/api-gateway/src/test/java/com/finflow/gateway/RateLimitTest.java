package com.finflow.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.finflow.testing.TestIssuer;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
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

/** Un quota par organisation : le client bruyant est freiné, les autres ne le sentent pas (ADR-017). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitTest {

    private static final TestIssuer ISSUER = TestIssuer.shared();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final HttpServer DOWNSTREAM = startDownstream();

    @LocalServerPort int port;
    WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwkSetUri);
        registry.add("TENANT_SERVICE_URL", () -> "http://localhost:" + DOWNSTREAM.getAddress().getPort());
        registry.add("finflow.gateway.rate-limit.requests-per-period", () -> "5");
        registry.add("finflow.gateway.rate-limit.period", () -> "1h");
    }

    @BeforeEach
    void setUp() {
        CALLS.set(0);
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    @AfterAll
    static void stop() {
        DOWNSTREAM.stop(0);
    }

    @Test
    void une_organisation_au_dela_de_son_quota_recoit_429_sans_atteindre_le_service() {
        String token = ISSUER.token().tenant(UUID.randomUUID()).build();

        for (int i = 0; i < 5; i++) {
            get(token).expectStatus().isOk();
        }
        get(token).expectStatus().isEqualTo(429).expectHeader().valueEquals("Retry-After", "3600");

        assertThat(CALLS).hasValue(5);
    }

    @Test
    void le_quota_d_une_organisation_n_entame_pas_celui_d_une_autre() {
        String bruyante = ISSUER.token().tenant(UUID.randomUUID()).build();
        String calme = ISSUER.token().tenant(UUID.randomUUID()).build();
        for (int i = 0; i < 6; i++) {
            get(bruyante).returnResult(String.class);
        }

        get(calme).expectStatus().isOk();
    }

    @Test
    void une_requete_refusee_par_l_authentification_ne_consomme_aucun_quota() {
        for (int i = 0; i < 10; i++) {
            client.get().uri("/api/v1/tenants/current").exchange().expectStatus().isUnauthorized();
        }
        get(ISSUER.token().tenant(UUID.randomUUID()).build()).expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec get(String token) {
        return client.get().uri("/api/v1/tenants/current").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private static HttpServer startDownstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                CALLS.incrementAndGet();
                byte[] body = "{}".getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
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
