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
    void un_transfert_valide_est_accepte_et_part_en_comptabilisation() throws Exception {
        UUID a = ACCOUNTS.add(alpha, "ACTIVE", 200_000, 1_000_000);
        UUID b = ACCOUNTS.add(alpha, "ACTIVE", 200_000, 1_000_000);
        String token = bearer(alpha, "tenant-member");

        initiate(token, key(), a, b, 150_000)
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/api/v1/payments/")))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.amountCents").value(150_000))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.version").value(3));

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
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].eventType")
                        .value(org.hamcrest.Matchers.contains("PaymentCreated", "PaymentValidationStarted",
                                "PaymentValidated")))
                .andExpect(jsonPath("$[*].version").value(org.hamcrest.Matchers.contains(1, 2, 3)))
                .andExpect(jsonPath("$[*].correlationId").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("corr-demo-1"))))
                .andExpect(jsonPath("$[0].causationId").doesNotExist())
                .andExpect(jsonPath("$[0].payload.amountCents").value(1_500))
                .andExpect(jsonPath("$[0].payload.tenantId").value(alpha.toString()));
        mvc.perform(get("/api/v1/payments/" + id).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
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
                .andExpect(jsonPath("$.version").value(3));
        mvc.perform(get("/api/v1/payments/" + first + "/events").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$", hasSize(3)));
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

        initiate(token, key(), a, b, 60_000).andExpect(jsonPath("$.status").value("PROCESSING"));
        initiate(token, key(), a, b, 50_000)
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejection.rule").value("LIMITE_JOURNALIERE"));
        // Un paiement rejeté n'entame pas la limite : 40 000 passent encore.
        initiate(token, key(), a, b, 40_000).andExpect(jsonPath("$.status").value("PROCESSING"));
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
                .andExpect(jsonPath("$.status").value("PROCESSING"));
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
        mvc.perform(get("/api/v1/payments?status=PROCESSING").header(HttpHeaders.AUTHORIZATION,
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
