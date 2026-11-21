package com.finflow.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Requêtes réellement simultanées, sur un vrai serveur : ce que les verrous et contraintes
 * garantissent quand le hasard des ordonnancements ne joue plus en notre faveur.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentConcurrencyIT extends PaymentIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort int port;

    @Test
    void dix_envois_simultanes_de_la_meme_demande_ne_creent_qu_un_paiement() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID a = ACCOUNTS.add(tenant, "ACTIVE", 100_000, 500_000);
        UUID b = ACCOUNTS.add(tenant, "ACTIVE", 100_000, 500_000);
        String token = bearer(tenant, "tenant-member");
        String key = key();

        List<HttpResponse<String>> responses = concurrently(10, i -> post(token, key, a, b, 4_200));

        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(202);
        assertThat(responses).extracting(r -> (String) JsonPath.read(r.body(), "$.id")).containsOnly(
                (String) JsonPath.read(responses.getFirst().body(), "$.id"));
        assertThat(responses).filteredOn(r -> r.headers().firstValue("Idempotent-Replayed").orElse("")
                .equals("false")).hasSize(1);
        assertThat(count("select count(*) from payment_summaries where tenant_id = ?", tenant)).isEqualTo(1);
        assertThat(count("select count(*) from payment_events where tenant_id = ?", tenant)).isEqualTo(3);
        assertThat(count("select count(*) from idempotency_keys where tenant_id = ?", tenant)).isEqualTo(1);
    }

    @Test
    void deux_paiements_simultanes_ne_depassent_jamais_la_limite_journaliere() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID tenant = UUID.randomUUID();
            UUID a = ACCOUNTS.add(tenant, "ACTIVE", 100_000, 100_000);
            UUID b = ACCOUNTS.add(tenant, "ACTIVE", 100_000, 100_000);
            String token = bearer(tenant, "tenant-member");

            List<HttpResponse<String>> responses = concurrently(2, i -> post(token, key(), a, b, 60_000));

            Map<String, Long> statuses = responses.stream()
                    .map(r -> (String) JsonPath.read(r.body(), "$.status"))
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            assertThat(statuses).as("tour %d", round).containsExactlyInAnyOrderEntriesOf(
                    Map.of("PROCESSING", 1L, "REJECTED", 1L));
        }
    }

    private HttpResponse<String> post(String token, String key, UUID source, UUID destination, long amount) {
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/payments"))
                    .header("Authorization", token)
                    .header("Idempotency-Key", key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"sourceAccountId":"%s","destinationAccountId":"%s","amountCents":%d}"""
                            .formatted(source, destination, amount)))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lance {@code n} appels qui partent au même instant. */
    private static <T> List<T> concurrently(int n, Function<Integer, T> call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.apply(index);
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }

    /** Lecture de contrôle en superutilisateur, hors RLS, pour compter ce qui a vraiment été écrit. */
    private static long count(String sql, UUID tenant) throws Exception {
        try (Connection c = DATABASE.superuserConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setObject(1, tenant);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
