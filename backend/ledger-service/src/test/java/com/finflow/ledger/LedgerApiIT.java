package com.finflow.ledger;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
class LedgerApiIT extends LedgerIntegrationTest {

    @Autowired MockMvc mvc;

    private UUID alpha;
    private UUID beta;
    private String admin;
    private UUID a;
    private UUID b;

    @BeforeEach
    void deux_comptes_dans_l_organisation_alpha() {
        ACCOUNTS.down(false);
        alpha = UUID.randomUUID();
        beta = UUID.randomUUID();
        admin = bearer(alpha, "tenant-admin");
        a = ACCOUNTS.add(alpha, "ACTIVE", 200_000, 1_000_000);
        b = ACCOUNTS.add(alpha, "ACTIVE", 200_000, 1_000_000);
    }

    // --- dépôts simulés --------------------------------------------------------------------------

    @Test
    void un_depot_credite_le_compte_et_debite_la_compensation() throws Exception {
        deposit(admin, key(), a, 10_000)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.kind").value("DEPOSIT"))
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[?(@.side=='CREDIT')].accountId").value(a.toString()))
                .andExpect(jsonPath("$.lines[?(@.side=='DEBIT')].amountCents").value(10_000));

        balance(admin, a).andExpect(jsonPath("$.balanceCents").value(10_000)).andExpect(jsonPath("$.currency")
                .value("EUR"));
    }

    @Test
    void rejouer_un_depot_avec_la_meme_cle_ne_credite_qu_une_fois() throws Exception {
        String key = key();
        String first = idOf(deposit(admin, key, a, 5_000).andExpect(status().isCreated()));

        deposit(admin, key, a, 5_000)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(first));
        balance(admin, a).andExpect(jsonPath("$.balanceCents").value(5_000));
    }

    @Test
    void la_meme_cle_avec_un_autre_montant_est_refusee() throws Exception {
        String key = key();
        deposit(admin, key, a, 5_000).andExpect(status().isCreated());

        deposit(admin, key, a, 6_000)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CLE_REUTILISEE"));
        balance(admin, a).andExpect(jsonPath("$.balanceCents").value(5_000));
    }

    @Test
    void seul_l_administrateur_peut_deposer() throws Exception {
        deposit(bearer(alpha, "tenant-member"), key(), a, 1_000).andExpect(status().isForbidden());
        deposit(bearer(alpha, "tenant-viewer"), key(), a, 1_000).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ledger/deposits").header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(depositBody(a, 1_000)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void un_depot_exige_un_compte_existant_actif_de_l_organisation() throws Exception {
        UUID closed = ACCOUNTS.add(alpha, "CLOSED", 1, 1);
        UUID foreign = ACCOUNTS.add(beta, "ACTIVE", 1, 1);

        deposit(admin, key(), UUID.randomUUID(), 1_000).andExpect(status().isNotFound());
        deposit(admin, key(), foreign, 1_000).andExpect(status().isNotFound());
        deposit(admin, key(), closed, 1_000).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMPTE_INACTIF"));
    }

    @Test
    void un_depot_attend_quand_le_service_des_comptes_est_en_panne() throws Exception {
        ACCOUNTS.down(true);

        deposit(admin, key(), a, 1_000).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COMPTES_INDISPONIBLES"));
    }

    @Test
    void les_montants_invalides_sont_refuses() throws Exception {
        deposit(admin, key(), a, 0).andExpect(status().isBadRequest());
        deposit(admin, key(), a, -5).andExpect(status().isBadRequest());
        deposit(admin, "court", a, 100).andExpect(status().isBadRequest());
    }

    // --- virements -------------------------------------------------------------------------------

    @Test
    void un_virement_debite_la_source_et_credite_la_destination() throws Exception {
        fund(a, 10_000);
        UUID payment = UUID.randomUUID();

        transfer(admin, payment, a, b, 2_500)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("TRANSFER"));

        balance(admin, a).andExpect(jsonPath("$.balanceCents").value(7_500));
        balance(admin, b).andExpect(jsonPath("$.balanceCents").value(2_500));
    }

    @Test
    void un_virement_sans_fonds_est_refuse_sans_rien_ecrire() throws Exception {
        fund(a, 1_000);

        transfer(admin, UUID.randomUUID(), a, b, 1_001)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FONDS_INSUFFISANTS"));

        balance(admin, a).andExpect(jsonPath("$.balanceCents").value(1_000));
        // Le refus ne laisse aucune trace : même le compte de destination n'a pas été créé.
        balance(admin, b).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/ledger/accounts/" + a + "/entries").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void rejouer_un_virement_renvoie_la_meme_transaction_sans_la_refaire() throws Exception {
        fund(a, 10_000);
        UUID payment = UUID.randomUUID();
        String first = idOf(transfer(admin, payment, a, b, 4_000).andExpect(status().isCreated()));

        // Même si la source n'a plus de quoi payer, le rejeu rend la transaction déjà passée.
        transfer(admin, UUID.randomUUID(), a, b, 6_000).andExpect(status().isCreated());
        transfer(admin, payment, a, b, 4_000)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(first));

        balance(admin, a).andExpect(jsonPath("$.balanceCents").value(0));
        balance(admin, b).andExpect(jsonPath("$.balanceCents").value(10_000));
    }

    @Test
    void un_meme_paiement_avec_un_autre_montant_est_refuse() throws Exception {
        fund(a, 10_000);
        UUID payment = UUID.randomUUID();
        transfer(admin, payment, a, b, 4_000).andExpect(status().isCreated());

        transfer(admin, payment, a, b, 4_001).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CLE_REUTILISEE"));
    }

    @Test
    void le_compte_d_une_autre_organisation_est_introuvable_et_rien_n_est_ecrit() throws Exception {
        fund(a, 10_000);
        UUID betaAccount = ACCOUNTS.add(beta, "ACTIVE", 1, 1);
        deposit(bearer(beta, "tenant-admin"), key(), betaAccount, 1_000).andExpect(status().isCreated());

        transfer(admin, UUID.randomUUID(), a, betaAccount, 100).andExpect(status().isNotFound());
        transfer(admin, UUID.randomUUID(), betaAccount, b, 100).andExpect(status().isNotFound());

        balance(admin, a).andExpect(jsonPath("$.balanceCents").value(10_000));
        balance(bearer(beta, "tenant-admin"), betaAccount).andExpect(jsonPath("$.balanceCents").value(1_000));
    }

    @Test
    void un_virement_vers_soi_meme_est_refuse() throws Exception {
        transfer(admin, UUID.randomUUID(), a, a, 100).andExpect(status().isBadRequest());
    }

    @Test
    void le_compte_de_compensation_n_est_pas_atteignable_par_un_client() throws Exception {
        fund(a, 1_000);
        String clearing = JsonPath.<java.util.List<String>>read(mvc.perform(get("/api/v1/ledger/transactions/"
                + lastDepositId)
                .header(HttpHeaders.AUTHORIZATION, admin)).andReturn().getResponse().getContentAsString(),
                "$.lines[?(@.side=='DEBIT')].accountId").getFirst();

        transfer(admin, UUID.randomUUID(), UUID.fromString(clearing), b, 500).andExpect(status().isNotFound());
        balance(admin, UUID.fromString(clearing)).andExpect(status().isNotFound());
    }

    // --- contrepassations ------------------------------------------------------------------------

    @Test
    void une_contrepassation_remet_les_soldes_comme_avant() throws Exception {
        fund(a, 10_000);
        String transaction = idOf(transfer(admin, UUID.randomUUID(), a, b, 3_000).andExpect(status().isCreated()));

        reverse(admin, UUID.randomUUID(), transaction)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("REVERSAL"))
                .andExpect(jsonPath("$.reversalOf").value(transaction));

        balance(admin, a).andExpect(jsonPath("$.balanceCents").value(10_000));
        balance(admin, b).andExpect(jsonPath("$.balanceCents").value(0));
    }

    @Test
    void rejouer_une_contrepassation_ne_la_refait_pas_et_une_autre_demande_est_refusee() throws Exception {
        fund(a, 10_000);
        String transaction = idOf(transfer(admin, UUID.randomUUID(), a, b, 3_000).andExpect(status().isCreated()));
        UUID refund = UUID.randomUUID();
        String first = idOf(reverse(admin, refund, transaction).andExpect(status().isCreated()));

        reverse(admin, refund, transaction).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(first));
        reverse(admin, UUID.randomUUID(), transaction).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NON_CONTREPASSABLE"));
        // Une contrepassation ne se contrepasse pas.
        reverse(admin, UUID.randomUUID(), first).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NON_CONTREPASSABLE"));

        balance(admin, a).andExpect(jsonPath("$.balanceCents").value(10_000));
    }

    @Test
    void on_ne_rembourse_pas_ce_que_le_destinataire_a_deja_depense() throws Exception {
        fund(a, 10_000);
        String transaction = idOf(transfer(admin, UUID.randomUUID(), a, b, 3_000).andExpect(status().isCreated()));
        UUID c = ACCOUNTS.add(alpha, "ACTIVE", 1, 1);
        transfer(admin, UUID.randomUUID(), b, c, 2_000).andExpect(status().isCreated());

        reverse(admin, UUID.randomUUID(), transaction).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FONDS_INSUFFISANTS"));

        balance(admin, a).andExpect(jsonPath("$.balanceCents").value(7_000));
        balance(admin, b).andExpect(jsonPath("$.balanceCents").value(1_000));
    }

    @Test
    void contrepasser_une_transaction_inconnue_ou_d_une_autre_organisation_est_introuvable() throws Exception {
        fund(a, 1_000);
        reverse(admin, UUID.randomUUID(), UUID.randomUUID().toString()).andExpect(status().isNotFound());
        reverse(bearer(beta, "tenant-admin"), UUID.randomUUID(), lastDepositId).andExpect(status().isNotFound());
    }

    // --- lecture ---------------------------------------------------------------------------------

    @Test
    void le_releve_liste_les_ecritures_du_compte_les_plus_recentes_d_abord() throws Exception {
        fund(a, 10_000);
        transfer(admin, UUID.randomUUID(), a, b, 1_000).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/ledger/accounts/" + a + "/entries").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].kind").value("TRANSFER"))
                .andExpect(jsonPath("$[0].side").value("DEBIT"))
                .andExpect(jsonPath("$[0].amountCents").value(1_000))
                .andExpect(jsonPath("$[1].kind").value("DEPOSIT"));
    }

    @Test
    void une_autre_organisation_ne_lit_ni_le_solde_ni_le_releve_ni_les_transactions() throws Exception {
        fund(a, 10_000);
        String other = bearer(beta, "tenant-admin");

        balance(other, a).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/ledger/accounts/" + a + "/entries").header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/ledger/transactions/" + lastDepositId).header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound());
    }

    @Test
    void les_ecritures_internes_exigent_un_jeton_valide_avec_un_role() throws Exception {
        String body = """
                {"paymentId":"%s","sourceAccountId":"%s","destinationAccountId":"%s","amountCents":100}"""
                .formatted(UUID.randomUUID(), a, b);
        mvc.perform(post("/internal/v1/ledger/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/ledger/transfers").header(HttpHeaders.AUTHORIZATION,
                        bearer(alpha, "tenant-viewer")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void un_jeton_sans_organisation_est_refuse() throws Exception {
        String noTenant = "Bearer " + ISSUER.token().roles("tenant-admin").build();
        balance(noTenant, a).andExpect(status().is4xxClientError());
    }

    // --- aides -----------------------------------------------------------------------------------

    private String lastDepositId;

    private void fund(UUID account, long cents) throws Exception {
        ResultActions result = deposit(admin, key(), account, cents).andExpect(status().isCreated());
        lastDepositId = idOf(result);
    }

    private ResultActions deposit(String token, String key, UUID account, long cents) throws Exception {
        return mvc.perform(post("/api/v1/ledger/deposits").header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(depositBody(account, cents)));
    }

    private static String depositBody(UUID account, long cents) {
        return """
                {"accountId":"%s","amountCents":%d,"description":"dépôt de test"}""".formatted(account, cents);
    }

    private ResultActions transfer(String token, UUID payment, UUID source, UUID destination, long cents)
            throws Exception {
        return mvc.perform(post("/internal/v1/ledger/transfers").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"paymentId":"%s","sourceAccountId":"%s","destinationAccountId":"%s","amountCents":%d}"""
                        .formatted(payment, source, destination, cents)));
    }

    private ResultActions reverse(String token, UUID refund, String transaction) throws Exception {
        return mvc.perform(post("/internal/v1/ledger/reversals").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"refundId":"%s","transactionId":"%s"}""".formatted(refund, transaction)));
    }

    private ResultActions balance(String token, UUID account) throws Exception {
        return mvc.perform(get("/api/v1/ledger/accounts/" + account + "/balance")
                .header(HttpHeaders.AUTHORIZATION, token));
    }

    private static String idOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
