package com.finflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Requêtes réellement simultanées sur un vrai serveur : ce que les verrous ordonnés, la clé
 * d'idempotence et la contrainte de solde garantissent quand l'ordonnancement n'aide plus.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerConcurrencyIT extends LedgerIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort int port;

    @Test
    void dix_envois_simultanes_du_meme_paiement_ne_le_comptabilisent_qu_une_fois() throws Exception {
        UUID tenant = UUID.randomUUID();
        String token = bearer(tenant, "tenant-admin");
        UUID a = fundedAccount(tenant, token, 10_000);
        UUID b = fundedAccount(tenant, token, 0);
        UUID payment = UUID.randomUUID();

        List<HttpResponse<String>> responses = concurrently(10, i -> transfer(token, payment, a, b, 4_000));

        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(200, 201);
        assertThat(responses).filteredOn(r -> r.statusCode() == 201).hasSize(1);
        assertThat(responses).extracting(r -> (String) JsonPath.read(r.body(), "$.id"))
                .containsOnly((String) JsonPath.read(responses.getFirst().body(), "$.id"));
        assertThat(balance(token, a)).isEqualTo(6_000);
        assertThat(balance(token, b)).isEqualTo(4_000);
    }

    @Test
    void des_virements_croises_en_masse_ne_s_interbloquent_pas_et_conservent_l_argent() throws Exception {
        UUID tenant = UUID.randomUUID();
        String token = bearer(tenant, "tenant-admin");
        UUID a = fundedAccount(tenant, token, 100_000);
        UUID b = fundedAccount(tenant, token, 100_000);

        List<HttpResponse<String>> responses = concurrently(40,
                i -> i % 2 == 0 ? transfer(token, UUID.randomUUID(), a, b, 100)
                        : transfer(token, UUID.randomUUID(), b, a, 100));

        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(201);
        assertThat(balance(token, a) + balance(token, b)).isEqualTo(200_000);
        assertThat(balance(token, a)).isEqualTo(100_000);
        assertThat(inconsistentAccounts(tenant)).isZero();
    }

    @Test
    void des_depenses_simultanees_ne_depassent_jamais_le_solde() throws Exception {
        for (int round = 0; round < 3; round++) {
            UUID tenant = UUID.randomUUID();
            String token = bearer(tenant, "tenant-admin");
            UUID a = fundedAccount(tenant, token, 1_000);
            UUID b = fundedAccount(tenant, token, 0);

            List<HttpResponse<String>> responses = concurrently(10, i -> transfer(token, UUID.randomUUID(), a, b, 300));

            assertThat(responses).as("tour %d", round).filteredOn(r -> r.statusCode() == 201).hasSize(3);
            assertThat(responses).filteredOn(r -> r.statusCode() == 422)
                    .hasSize(7)
                    .allMatch(r -> JsonPath.<String>read(r.body(), "$.code").equals("FONDS_INSUFFISANTS"));
            assertThat(balance(token, a)).isEqualTo(100);
            assertThat(balance(token, b)).isEqualTo(900);
            assertThat(inconsistentAccounts(tenant)).isZero();
        }
    }

    @Test
    void deux_contrepassations_simultanees_de_la_meme_transaction_n_en_passent_qu_une() throws Exception {
        UUID tenant = UUID.randomUUID();
        String token = bearer(tenant, "tenant-admin");
        UUID a = fundedAccount(tenant, token, 5_000);
        UUID b = fundedAccount(tenant, token, 0);
        String transaction = JsonPath.read(transfer(token, UUID.randomUUID(), a, b, 2_000).body(), "$.id");

        List<HttpResponse<String>> responses = concurrently(6, i -> reverse(token, UUID.randomUUID(), transaction));

        assertThat(responses).filteredOn(r -> r.statusCode() == 201).hasSize(1);
        assertThat(responses).filteredOn(r -> r.statusCode() == 409).hasSize(5);
        assertThat(balance(token, a)).isEqualTo(5_000);
        assertThat(balance(token, b)).isZero();
    }

    // --- aides -----------------------------------------------------------------------------------

    private UUID fundedAccount(UUID tenant, String token, long cents) throws Exception {
        UUID account = ACCOUNTS.add(tenant, "ACTIVE", 1_000_000, 10_000_000);
        if (cents > 0) {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/v1/ledger/deposits"))
                    .header("Authorization", token).header("Idempotency-Key", key())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"accountId":"%s","amountCents":%d}""".formatted(account, cents))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
        }
        return account;
    }

    private HttpResponse<String> transfer(String token, UUID payment, UUID source, UUID destination, long cents) {
        return send("/internal/v1/ledger/transfers", token, """
                {"paymentId":"%s","sourceAccountId":"%s","destinationAccountId":"%s","amountCents":%d}"""
                .formatted(payment, source, destination, cents));
    }

    private HttpResponse<String> reverse(String token, UUID refund, String transaction) {
        return send("/internal/v1/ledger/reversals", token, """
                {"refundId":"%s","transactionId":"%s"}""".formatted(refund, transaction));
    }

    private HttpResponse<String> send(String path, String token, String body) {
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Authorization", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long balance(String token, UUID account) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/ledger/accounts/" + account + "/balance"))
                .header("Authorization", token).GET().build(), HttpResponse.BodyHandlers.ofString());
        return ((Number) JsonPath.read(response.body(), "$.balanceCents")).longValue();
    }

    private long inconsistentAccounts(UUID tenant) throws Exception {
        try (var c = DATABASE.superuserConnection(); var st = c.createStatement();
             var rs = st.executeQuery("select count(*) from comptes_incoherents() i join ledger_accounts a "
                     + "on a.id = i.account_id where a.tenant_id = '" + tenant + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Lance {@code n} appels au même instant et les attend tous. */
    private <T> List<T> concurrently(int n, IntFunction<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.apply(index);
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
