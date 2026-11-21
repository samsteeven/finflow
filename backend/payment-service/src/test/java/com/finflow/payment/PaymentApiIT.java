package com.finflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentApiIT extends PaymentIntegrationTest {

    @Autowired MockMvc mvc;

    private final UUID alpha = UUID.randomUUID();
    private final UUID beta = UUID.randomUUID();

    @Test
    void un_transfert_valide_est_comptabilise_puis_termine() throws Exception {
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 200_000, 1_000_000);
        UUID b = ACCOUNTS.add(alpha, "ACTIVE", 200_000, 1_000_000);
        String token = bearer(alpha, "tenant-member");

        initiate(token, key(), a, b, 150_000)
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/api/v1/payments/")))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amountCents").value(150_000))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.ledgerTransactionId").exists())
                .andExpect(jsonPath("$.version").value(4));

        assertThat(ACCOUNTS.receivedTokens()).contains(token.substring("Bearer ".length()));
    }

    @Test
    void l_historique_du_paiement_se_lit_evenement_par_evenement() throws Exception {
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 200_000, 1_000_000);
        UUID b = ACCOUNTS.add(alpha, "ACTIVE", 200_000, 1_000_000);
        String token = bearer(alpha, "tenant-admin");
        String id = idOf(mvc.perform(post("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", key()).header("X-Correlation-Id", "corr-demo-1")
                .contentType(MediaType.APPLICATION_JSON).content(body(a, b, 1_500))));

        mvc.perform(get("/api/v1/payments/" + id + "/events").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].eventType")
                        .value(org.hamcrest.Matchers.contains("PaymentCreated", "PaymentValidationStarted",
                                "PaymentValidated", "PaymentCompleted")))
                .andExpect(jsonPath("$[*].version").value(org.hamcrest.Matchers.contains(1, 2, 3, 4)))
                .andExpect(jsonPath("$[*].correlationId").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("corr-demo-1"))))
                .andExpect(jsonPath("$[0].causationId").doesNotExist())
                .andExpect(jsonPath("$[0].payload.amountCents").value(1_500))
                .andExpect(jsonPath("$[0].payload.tenantId").value(alpha.toString()));
        mvc.perform(get("/api/v1/payments/" + id).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void un_rejeu_de_la_meme_cle_renvoie_le_meme_paiement_sans_rien_recreer() throws Exception {
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 200_000, 1_000_000);
        UUID b = ACCOUNTS.add(alpha, "ACTIVE", 200_000, 1_000_000);
        String token = bearer(alpha, "tenant-member");
        String key = key();

        String first = idOf(initiate(token, key, a, b, 2_000));
        initiate(token, key, a, b, 2_000)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.version").value(4));
        mvc.perform(get("/api/v1/payments/" + first + "/events").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    void une_cle_reutilisee_pour_une_autre_demande_est_refusee() throws Exception {
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 200_000, 1_000_000);
        UUID b = ACCOUNTS.add(alpha, "ACTIVE", 200_000, 1_000_000);
        String token = bearer(alpha, "tenant-member");
        String key = key();
        initiate(token, key, a, b, 2_000).andExpect(status().isAccepted());

        initiate(token, key, a, b, 9_999)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Clé d'idempotence déjà utilisée"));
    }

    @Test
    void deux_organisations_peuvent_utiliser_la_meme_cle() throws Exception {
        String key = key();
        String alphaPayment = idOf(initiate(bearer(alpha, "tenant-member"), key,
                ACCOUNTS.add(alpha, "ACTIVE", 10_000, 10_000), ACCOUNTS.add(alpha, "ACTIVE", 10_000, 10_000), 100));
        String betaPayment = idOf(initiate(bearer(beta, "tenant-member"), key,
                ACCOUNTS.add(beta, "ACTIVE", 10_000, 10_000), ACCOUNTS.add(beta, "ACTIVE", 10_000, 10_000), 100));

        assertThat(alphaPayment).isNotEqualTo(betaPayment);
    }

    @Test
    void la_cle_d_idempotence_est_obligatoire_et_de_longueur_raisonnable() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        mvc.perform(post("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, bearer(alpha, "tenant-member"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(a, b, 100)))
                .andExpect(status().isBadRequest());
        initiate(bearer(alpha, "tenant-member"), "court", a, b, 100).andExpect(status().isBadRequest());
    }

    @Test
    void les_regles_de_risque_rejettent_avec_un_motif_explicable() throws Exception {
        String token = bearer(alpha, "tenant-member");
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 500_000);
        UUID b = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 500_000);
        UUID frozen = ACCOUNTS.add(alpha, "FROZEN", 100_000, 500_000);
        UUID betaAccount = ACCOUNTS.add(beta, "ACTIVE", 100_000, 500_000);

        initiate(token, key(), a, b, 150_000)
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejection.rule").value("LIMITE_PAR_OPERATION"))
                .andExpect(jsonPath("$.rejection.detail").value("montant 150000 > limite par opération 100000"));
        initiate(token, key(), a, frozen, 1_000)
                .andExpect(jsonPath("$.rejection.rule").value("COMPTE_DESTINATAIRE_INACTIF"));
        // Le compte d'une autre organisation est introuvable pour Alpha, comme un compte inexistant.
        initiate(token, key(), betaAccount, b, 1_000)
                .andExpect(jsonPath("$.rejection.rule").value("COMPTE_SOURCE_INTROUVABLE"));
        initiate(token, key(), a, UUID.randomUUID(), 1_000)
                .andExpect(jsonPath("$.rejection.rule").value("COMPTE_DESTINATAIRE_INTROUVABLE"));
    }

    @Test
    void la_limite_journaliere_compte_les_paiements_deja_engages() throws Exception {
        String token = bearer(alpha, "tenant-member");
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 60_000, 100_000);
        UUID b = ACCOUNTS.add(alpha, "ACTIVE", 60_000, 100_000);

        initiate(token, key(), a, b, 60_000).andExpect(jsonPath("$.status").value("COMPLETED"));
        initiate(token, key(), a, b, 50_000)
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejection.rule").value("LIMITE_JOURNALIERE"));
        // Un paiement rejeté n'entame pas la limite : 40 000 passent encore.
        initiate(token, key(), a, b, 40_000).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void une_panne_d_account_laisse_le_paiement_en_validation_et_le_rejeu_le_reprend() throws Exception {
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 500_000);
        UUID b = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 500_000);
        String token = bearer(alpha, "tenant-member");
        String key = key();

        ACCOUNTS.down(true);
        String id = idOf(initiate(token, key, a, b, 5_000).andExpect(jsonPath("$.status").value("VALIDATING")));

        ACCOUNTS.down(false);
        initiate(token, key, a, b, 5_000)
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void un_paiement_vers_le_meme_compte_ou_dans_une_autre_devise_est_invalide() throws Exception {
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 500_000);
        String token = bearer(alpha, "tenant-member");
        initiate(token, key(), a, a, 100).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Paiement invalide"));
        mvc.perform(post("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, token).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":100,\"currency\":\"USD\"}"
                                .formatted(a, UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        initiate(token, key(), a, UUID.randomUUID(), 0).andExpect(status().isBadRequest());
    }

    @Test
    void une_organisation_ne_voit_ni_les_paiements_ni_l_historique_d_une_autre() throws Exception {
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 500_000);
        UUID b = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 500_000);
        String id = idOf(initiate(bearer(alpha, "tenant-member"), key(), a, b, 700));
        String betaToken = bearer(beta, "tenant-admin");

        mvc.perform(get("/api/v1/payments/" + id).header(HttpHeaders.AUTHORIZATION, betaToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/payments/" + id + "/events").header(HttpHeaders.AUTHORIZATION, betaToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, betaToken))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/payments?status=COMPLETED").header(HttpHeaders.AUTHORIZATION,
                        bearer(alpha, "tenant-member")))
                .andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.hasItem(id)));
    }

    @Test
    void sans_jeton_sans_organisation_ou_sans_role_metier_l_acces_est_refuse() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        mvc.perform(get("/api/v1/payments")).andExpect(status().isUnauthorized());
        initiate("Bearer " + ISSUER.token().roles("tenant-member").build(), key(), a, b, 100)
                .andExpect(status().isForbidden());
        initiate("Bearer " + ISSUER.token().tenant(alpha).build(), key(), a, b, 100)
                .andExpect(status().isForbidden());
    }

    // --- comptabilisation par le Ledger (ADR-012) -----------------------------------------------

    @Test
    void des_fonds_insuffisants_rejettent_le_paiement_avec_le_motif_du_ledger() throws Exception {
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 100_000);
        UUID b = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 100_000);
        String token = bearer(alpha, "tenant-member");

        LEDGER.insufficientFunds(true);
        String id = idOf(initiate(token, key(), a, b, 60_000)
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejection.rule").value("FONDS_INSUFFISANTS"))
                .andExpect(jsonPath("$.ledgerTransactionId").doesNotExist()));
        mvc.perform(get("/api/v1/payments/" + id + "/events").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$[*].eventType").value(org.hamcrest.Matchers.contains("PaymentCreated",
                        "PaymentValidationStarted", "PaymentValidated", "PaymentRejected")));

        // Un paiement rejeté par le Ledger n'entame pas la limite journalière.
        LEDGER.insufficientFunds(false);
        initiate(token, key(), a, b, 100_000).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void une_panne_du_ledger_met_le_paiement_en_echec_et_le_rejeu_le_reprend_sans_double_ecriture()
            throws Exception {
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 500_000);
        UUID b = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 500_000);
        String token = bearer(alpha, "tenant-member");
        String key = key();
        int before = LEDGER.postedTransactions();

        LEDGER.down(true);
        String id = idOf(initiate(token, key, a, b, 5_000).andExpect(jsonPath("$.status").value("FAILED")));
        assertThat(LEDGER.postedTransactions()).isEqualTo(before);

        LEDGER.down(false);
        initiate(token, key, a, b, 5_000)
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.ledgerTransactionId").exists());
        assertThat(LEDGER.postedTransactions()).isEqualTo(before + 1);
        mvc.perform(get("/api/v1/payments/" + id + "/events").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$[*].eventType").value(org.hamcrest.Matchers.contains("PaymentCreated",
                        "PaymentValidationStarted", "PaymentValidated", "PaymentFailed", "PaymentRetried",
                        "PaymentCompleted")));
    }

    @Test
    void une_reponse_perdue_apres_comptabilisation_ne_double_pas_l_ecriture_a_la_reprise() throws Exception {
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 500_000);
        UUID b = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 500_000);
        String token = bearer(alpha, "tenant-member");
        String key = key();
        int before = LEDGER.postedTransactions();

        // Le Ledger comptabilise, mais Payment ne reçoit jamais la réponse : l'issue est inconnue.
        LEDGER.loseNextResponses(1);
        initiate(token, key, a, b, 7_000).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(LEDGER.postedTransactions()).isEqualTo(before + 1);

        initiate(token, key, a, b, 7_000).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(LEDGER.postedTransactions()).isEqualTo(before + 1);
    }

    @Test
    void les_reprises_apres_echec_sont_limitees_a_trois() throws Exception {
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 500_000);
        UUID b = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 500_000);
        String token = bearer(alpha, "tenant-member");
        String key = key();
        LEDGER.down(true);
        int callsBefore = LEDGER.calls();

        initiate(token, key, a, b, 1_000).andExpect(jsonPath("$.status").value("FAILED"));
        for (int retry = 0; retry < 3; retry++) {
            initiate(token, key, a, b, 1_000).andExpect(jsonPath("$.status").value("FAILED"));
        }
        assertThat(LEDGER.calls()).isEqualTo(callsBefore + 4);

        // La quatrième reprise n'appelle plus le Ledger, même rétabli.
        LEDGER.down(false);
        initiate(token, key, a, b, 1_000).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(LEDGER.calls()).isEqualTo(callsBefore + 4);
    }

    @Test
    void le_jeton_de_l_utilisateur_est_relaye_au_ledger() throws Exception {
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 500_000);
        UUID b = ACCOUNTS.add(alpha, "ACTIVE", 100_000, 500_000);
        String token = bearer(alpha, "tenant-member");

        initiate(token, key(), a, b, 1_000).andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(LEDGER.receivedTokens()).contains(token.substring("Bearer ".length()));
    }

    // --- remboursements ---------------------------------------------------------------------------

    @Test
    void un_remboursement_contrepasse_le_paiement() throws Exception {
        String id = completedPayment(1_500);
        int before = LEDGER.postedTransactions();

        refund(id, bearer(alpha, "tenant-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundLedgerTransactionId").exists());

        assertThat(LEDGER.postedTransactions()).isEqualTo(before + 1);
        mvc.perform(get("/api/v1/payments/" + id + "/events").header(HttpHeaders.AUTHORIZATION,
                        bearer(alpha, "tenant-admin")))
                .andExpect(jsonPath("$[*].eventType").value(org.hamcrest.Matchers.hasItems("RefundRequested",
                        "RefundCompleted")));
    }

    @Test
    void seul_l_administrateur_rembourse_et_une_seule_fois() throws Exception {
        String id = completedPayment(1_500);

        refund(id, bearer(alpha, "tenant-member")).andExpect(status().isForbidden());
        refund(id, bearer(alpha, "tenant-admin")).andExpect(status().isOk());
        refund(id, bearer(alpha, "tenant-admin")).andExpect(status().isConflict());
    }

    @Test
    void on_ne_rembourse_ni_un_paiement_rejete_ni_celui_d_une_autre_organisation() throws Exception {
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 1_000, 1_000);
        UUID b = ACCOUNTS.add(alpha, "ACTIVE", 1_000, 1_000);
        String rejected = idOf(initiate(bearer(alpha, "tenant-member"), key(), a, b, 5_000));
        String completed = completedPayment(900);

        refund(rejected, bearer(alpha, "tenant-admin")).andExpect(status().isConflict());
        refund(completed, bearer(beta, "tenant-admin")).andExpect(status().isNotFound());
        refund(UUID.randomUUID().toString(), bearer(alpha, "tenant-admin")).andExpect(status().isNotFound());
    }

    @Test
    void un_remboursement_en_attente_du_ledger_se_reprend_sans_double_contrepassation() throws Exception {
        String id = completedPayment(2_000);
        String admin = bearer(alpha, "tenant-admin");
        int before = LEDGER.postedTransactions();

        LEDGER.down(true);
        refund(id, admin).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUND_PENDING"));

        // Réponse perdue : le Ledger a contrepassé, Payment l'ignore. La reprise réutilise la même clé.
        LEDGER.down(false);
        LEDGER.loseNextResponses(1);
        refund(id, admin).andExpect(jsonPath("$.status").value("REFUND_PENDING"));
        assertThat(LEDGER.postedTransactions()).isEqualTo(before + 1);

        refund(id, admin).andExpect(jsonPath("$.status").value("REFUNDED"));
        assertThat(LEDGER.postedTransactions()).isEqualTo(before + 1);
    }

    @Test
    void un_remboursement_refuse_par_le_ledger_laisse_le_paiement_termine_et_remboursable_plus_tard()
            throws Exception {
        String id = completedPayment(2_000);
        String admin = bearer(alpha, "tenant-admin");

        LEDGER.reversalRefused(true);
        refund(id, admin)
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.refundRejection").value(org.hamcrest.Matchers.containsString(
                        "FONDS_INSUFFISANTS")));

        LEDGER.reversalRefused(false);
        refund(id, admin).andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void une_transaction_deja_contrepassee_cote_ledger_ferme_la_demande() throws Exception {
        String id = completedPayment(2_000);

        LEDGER.notReversible(true);
        refund(id, bearer(alpha, "tenant-admin")).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.refundRejection").value(org.hamcrest.Matchers.containsString(
                        "NON_CONTREPASSABLE")));
    }

    private String completedPayment(long amount) throws Exception {
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 1_000_000, 10_000_000);
        UUID b = ACCOUNTS.add(alpha, "ACTIVE", 1_000_000, 10_000_000);
        return idOf(initiate(bearer(alpha, "tenant-member"), key(), a, b, amount)
                .andExpect(jsonPath("$.status").value("COMPLETED")));
    }

    private ResultActions refund(String paymentId, String token) throws Exception {
        return mvc.perform(post("/api/v1/payments/" + paymentId + "/refunds")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"erreur de destinataire\"}"));
    }

    private ResultActions initiate(String token, String key, UUID source, UUID destination, long amount)
            throws Exception {
        return mvc.perform(post("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(source, destination, amount)));
    }

    private static String body(UUID source, UUID destination, long amount) {
        return """
                {"sourceAccountId":"%s","destinationAccountId":"%s","amountCents":%d,"description":"Transfert"}"""
                .formatted(source, destination, amount);
    }

    private static String idOf(ResultActions response) throws Exception {
        return JsonPath.read(response.andExpect(status().isAccepted()).andReturn().getResponse()
                .getContentAsString(), "$.id");
    }
}
