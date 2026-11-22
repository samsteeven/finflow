package com.finflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.finflow.platform.Resilience;
import com.jayway.jsonpath.JsonPath;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Protection des appels vers le Ledger et Account (ADR-017) : un service malade est vite écarté,
 * le paiement reste repris par la saga, et le circuit se referme quand le service revient.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LedgerProtectionIT extends PaymentIntegrationTest {

    @DynamicPropertySource
    static void strictProtection(DynamicPropertyRegistry registry) {
        // Réglages serrés, enregistrés après ceux de la classe mère (qui désactive le circuit pour les autres tests).
        registry.add("finflow.resilience.circuit-breaker.minimum-calls", () -> "3");
        registry.add("finflow.resilience.circuit-breaker.sliding-window-size", () -> "4");
        registry.add("finflow.resilience.circuit-breaker.open-duration", () -> "1500ms");
        registry.add("finflow.resilience.circuit-breaker.half-open-calls", () -> "1");
    }

    @Autowired MockMvc mvc;
    @Autowired Resilience resilience;

    private UUID tenant;
    private UUID a;
    private UUID b;

    @BeforeEach
    void comptes_et_circuit_ferme() throws Exception {
        tenant = UUID.randomUUID();
        a = ACCOUNTS.add(tenant, "ACTIVE", 200_000, 1_000_000);
        b = ACCOUNTS.add(tenant, "ACTIVE", 200_000, 1_000_000);
        LEDGER.down(false);
        // Un appel réussi pour que le circuit, partagé entre les tests de cette classe, soit refermé.
        awaitClosed();
    }

    /** Les paiements en échec laissés ici deviendraient dus et prendraient la place de ceux des autres classes. */
    @AfterEach
    void oublier_les_sagas_en_attente() throws Exception {
        try (var c = DATABASE.superuserConnection(); var st = c.createStatement()) {
            st.execute("delete from saga_watch");
        }
    }

    @Test
    void un_ledger_en_panne_ouvre_le_circuit_puis_les_appels_suivants_ne_partent_plus() throws Exception {
        LEDGER.down(true);
        for (int i = 0; i < 3; i++) {
            assertThat(status(pay())).isEqualTo("FAILED");
        }
        assertThat(resilience.state("ledger")).isEqualTo(CircuitBreaker.State.OPEN);

        LEDGER.clearRecordedCalls();
        String refused = pay();

        // Le paiement est resté en échec technique, repris plus tard par la saga, sans avoir sollicité le Ledger.
        assertThat(status(refused)).isEqualTo("FAILED");
        assertThat(LEDGER.callsFor("paymentId:" + refused)).isZero();
        assertThat(LEDGER.calls()).isZero();
    }

    @Test
    void le_circuit_se_referme_quand_le_ledger_revient_et_le_paiement_repris_n_est_pas_double() throws Exception {
        LEDGER.down(true);
        String first = pay();
        for (int i = 0; i < 2; i++) {
            pay();
        }
        assertThat(resilience.state("ledger")).isEqualTo(CircuitBreaker.State.OPEN);

        LEDGER.down(false);
        Thread.sleep(1_800); // durée d'ouverture écoulée : le circuit passe en demi-ouvert
        String again = replay(first);

        assertThat(again).isEqualTo("COMPLETED");
        assertThat(resilience.state("ledger")).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(LEDGER.posted("paymentId:" + first)).isTrue();
    }

    @Test
    void un_refus_metier_du_ledger_n_ouvre_jamais_le_circuit() throws Exception {
        LEDGER.insufficientFunds(true);
        for (int i = 0; i < 8; i++) {
            assertThat(status(pay())).isEqualTo("REJECTED");
        }

        assertThat(resilience.state("ledger")).isEqualTo(CircuitBreaker.State.CLOSED);
        LEDGER.insufficientFunds(false);
    }

    @Test
    void account_en_panne_ouvre_son_propre_circuit_sans_toucher_celui_du_ledger() throws Exception {
        ACCOUNTS.down(true);
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, bearer(tenant, "tenant-member"))
                    .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(body()));
        }

        assertThat(resilience.state("account")).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(resilience.state("ledger")).isEqualTo(CircuitBreaker.State.CLOSED);
        ACCOUNTS.down(false);
        Thread.sleep(1_800);
    }

    // --- aides -----------------------------------------------------------------------------------

    private String body() {
        return """
                {"sourceAccountId":"%s","destinationAccountId":"%s","amountCents":1000,"currency":"EUR"}"""
                .formatted(a, b);
    }

    private String pay() throws Exception {
        String response = mvc.perform(post("/api/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenant, "tenant-member"))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    @Autowired com.finflow.payment.application.PaymentService payments;

    /** Reprend un paiement échoué comme le ferait le worker : même clé comptable, donc pas de double écriture. */
    private String replay(String paymentId) {
        return payments.recover(tenant, UUID.fromString(paymentId)).status().name();
    }

    private String status(String id) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/v1/payments/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant, "tenant-member")))
                .andReturn().getResponse().getContentAsString(), "$.status");
    }

    private void awaitClosed() throws Exception {
        long deadline = System.nanoTime() + 6_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (resilience.state("ledger") == CircuitBreaker.State.CLOSED
                    && resilience.state("account") == CircuitBreaker.State.CLOSED) {
                return;
            }
            Thread.sleep(200);
            pay(); // une demi-ouverture se referme sur un appel réussi
        }
    }
}
