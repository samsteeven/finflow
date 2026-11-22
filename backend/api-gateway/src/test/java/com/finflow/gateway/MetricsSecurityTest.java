package com.finflow.gateway;

import com.finflow.testing.TestIssuer;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Les métriques exposent le trafic et l'activité : seul le rôle « metrics » (client Prometheus) les lit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsSecurityTest {

    private static final TestIssuer ISSUER = TestIssuer.shared();

    @LocalServerPort int port;
    WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwkSetUri);
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void sans_jeton_les_metriques_sont_refusees() {
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void un_utilisateur_de_l_organisation_ne_lit_pas_les_metriques() {
        String token = ISSUER.token().tenant(UUID.randomUUID()).roles("tenant-admin").build();

        client.get().uri("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void le_client_prometheus_lit_les_metriques_metier_et_techniques() {
        String token = ISSUER.token().roles("metrics").build();

        client.get().uri("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody(String.class).value(body -> {
                    org.assertj.core.api.Assertions.assertThat(body)
                            .contains("jvm_memory_used_bytes", "application=\"api-gateway\"");
                });
    }

    @Test
    void la_sante_reste_publique_pour_les_sondes() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
