package com.finflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.finflow.payment.application.RecoveryWorker;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Reprise automatique de la saga (ADR-015) : un paiement resté en suspens finit seul, avec la
 * même clé comptable, sous l'organisation du paiement et avec le jeton du compte de service.
 */
@SpringBootTest(properties = {"finflow.saga.recovery.enabled=true", "finflow.saga.recovery.interval=1h",
        "finflow.saga.recovery.base-delay=1h"})
@AutoConfigureMockMvc
class SagaRecoveryIT extends PaymentIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired RecoveryWorker worker;

    private UUID tenant;
    private UUID a;
    private UUID b;

    @BeforeEach
    void deux_comptes() throws Exception {
        // Les sagas laissées par les autres classes finissent par être dues et prendraient la place du lot de reprise.
        execute("delete from saga_watch");
        tenant = UUID.randomUUID();
        a = ACCOUNTS.add(tenant, "ACTIVE", 200_000, 1_000_000);
        b = ACCOUNTS.add(tenant, "ACTIVE", 200_000, 1_000_000);
        LEDGER.clearRecordedCalls();
    }

    @Test
    void un_paiement_echoue_pendant_une_panne_du_ledger_finit_seul_sans_double_ecriture() throws Exception {
        LEDGER.down(true);
        String id = pay(a, b, 12_000);
        assertThat(status(id)).isEqualTo("FAILED");

        LEDGER.down(false);
        age(id);
        int taken = worker.runOnce();

        assertThat(taken).isGreaterThanOrEqualTo(1);
        assertThat(status(id)).isEqualTo("COMPLETED");
        assertThat(LEDGER.posted("paymentId:" + id)).isTrue();
        assertThat(LEDGER.callsFor("paymentId:" + id)).isEqualTo(2); // l'échec, puis la reprise
        // La reprise n'a pas de requête utilisateur : jeton de service et organisation désignée.
        assertThat(LEDGER.receivedTenants()).contains(tenant.toString());
        assertThat(LEDGER.receivedTokens().getLast()).isEqualTo(TestServiceToken.VALUE);
    }

    @Test
    void une_reponse_perdue_apres_comptabilisation_ne_comptabilise_pas_deux_fois() throws Exception {
        LEDGER.loseNextResponses(1);
        String id = pay(a, b, 12_000);
        assertThat(status(id)).isEqualTo("FAILED");

        age(id);
        worker.runOnce();

        assertThat(status(id)).isEqualTo("COMPLETED");
        assertThat(LEDGER.posted("paymentId:" + id)).isTrue();
        assertThat(LEDGER.postedTransactions()).isEqualTo(1);
    }

    @Test
    void une_reprise_attend_son_delai() throws Exception {
        LEDGER.down(true);
        String id = pay(a, b, 12_000);
        LEDGER.down(false);

        // Le paiement vient d'échouer : le délai de reprise n'est pas écoulé.
        assertThat(worker.runOnce()).isZero();
        assertThat(status(id)).isEqualTo("FAILED");
    }

    @Test
    void le_delai_double_a_chaque_reprise() throws Exception {
        LEDGER.down(true);
        String id = pay(a, b, 12_000);
        assertThat(LEDGER.callsFor("paymentId:" + id)).isEqualTo(1);

        age(id, "2 hours"); // délai de la première reprise : 1 h
        worker.runOnce();
        assertThat(LEDGER.callsFor("paymentId:" + id)).isEqualTo(2);

        age(id, "90 minutes"); // la deuxième reprise attend 2 h
        worker.runOnce();
        assertThat(LEDGER.callsFor("paymentId:" + id)).isEqualTo(2);

        age(id, "3 hours");
        worker.runOnce();
        assertThat(LEDGER.callsFor("paymentId:" + id)).isEqualTo(3);
    }

    @Test
    void apres_trois_reprises_le_paiement_attend_une_decision_humaine() throws Exception {
        LEDGER.down(true);
        String id = pay(a, b, 12_000);

        for (int i = 0; i < 6; i++) {
            age(id);
            worker.runOnce();
        }

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(LEDGER.callsFor("paymentId:" + id)).isEqualTo(4); // la tentative initiale et trois reprises
        assertThat(scalar("select count(*) from payment_events where payment_id = '" + id
                + "' and event_type = 'PaymentRetried'")).isEqualTo(3);
    }

    @Test
    void un_remboursement_en_attente_est_repris() throws Exception {
        String id = pay(a, b, 12_000);
        assertThat(status(id)).isEqualTo("COMPLETED");
        LEDGER.down(true);
        mvc.perform(post("/api/v1/payments/" + id + "/refunds").header(HttpHeaders.AUTHORIZATION,
                        bearer(tenant, "tenant-admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"erreur\"}"));
        assertThat(status(id)).isEqualTo("REFUND_PENDING");

        LEDGER.down(false);
        age(id);
        worker.runOnce();

        assertThat(status(id)).isEqualTo("REFUNDED");
    }

    @Test
    void chaque_reprise_s_execute_dans_l_organisation_de_son_paiement() throws Exception {
        UUID other = UUID.randomUUID();
        UUID c = ACCOUNTS.add(other, "ACTIVE", 200_000, 1_000_000);
        UUID d = ACCOUNTS.add(other, "ACTIVE", 200_000, 1_000_000);
        LEDGER.down(true);
        String mine = pay(a, b, 12_000);
        String theirs = pay(other, c, d, 3_000);
        LEDGER.down(false);
        age(mine);
        age(theirs);

        worker.runOnce();

        assertThat(status(mine)).isEqualTo("COMPLETED");
        assertThat(statusOf(other, theirs)).isEqualTo("COMPLETED");
        assertThat(LEDGER.receivedTenants()).contains(tenant.toString(), other.toString());
    }

    // --- aides -----------------------------------------------------------------------------------

    private String pay(UUID source, UUID destination, long cents) throws Exception {
        return pay(tenant, source, destination, cents);
    }

    private String pay(UUID owner, UUID source, UUID destination, long cents) throws Exception {
        String response = mvc.perform(post("/api/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner, "tenant-member"))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s","amountCents":%d,"currency":"EUR"}"""
                                .formatted(source, destination, cents)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private String status(String id) throws Exception {
        return statusOf(tenant, id);
    }

    private String statusOf(UUID owner, String id) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/v1/payments/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer(owner, "tenant-member")))
                .andReturn().getResponse().getContentAsString(), "$.status");
    }

    /** Fait vieillir un paiement pour que son délai de reprise soit écoulé ; les autres, récents, ne sont pas dus. */
    private static void age(String paymentId) throws Exception {
        age(paymentId, "1 day");
    }

    private static void age(String paymentId, String interval) throws Exception {
        execute("update saga_watch set updated_at = now() - interval '" + interval + "' where payment_id = '"
                + paymentId + "'");
    }

    private static void execute(String sql) throws Exception {
        try (Connection c = DATABASE.superuserConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static long scalar(String sql) throws Exception {
        try (Connection c = DATABASE.superuserConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
