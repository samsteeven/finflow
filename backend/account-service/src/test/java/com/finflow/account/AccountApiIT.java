package com.finflow.account;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finflow.testing.TestIssuer;
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
class AccountApiIT extends PostgresIntegrationTest {

    private static final TestIssuer ISSUER = TestIssuer.shared();

    @Autowired MockMvc mvc;

    private final UUID alpha = UUID.randomUUID();
    private final UUID beta = UUID.randomUUID();

    @Test
    void un_administrateur_ouvre_un_compte_en_euros_dans_son_organisation() throws Exception {
        open(admin(alpha), "Compte courant", 100_000, 500_000)
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.limits.perTransactionCents").value(100_000))
                .andExpect(jsonPath("$.limits.dailyCents").value(500_000));
    }

    @Test
    void un_simple_membre_ne_peut_ni_ouvrir_ni_geler_un_compte() throws Exception {
        String id = idOf(open(admin(alpha), "A", 1_000, 1_000));
        open(member(alpha), "B", 1_000, 1_000).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/accounts/" + id + "/freeze").header(HttpHeaders.AUTHORIZATION, member(alpha)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts/" + id).header(HttpHeaders.AUTHORIZATION, member(alpha)))
                .andExpect(status().isOk());
    }

    @Test
    void une_devise_autre_que_l_euro_ou_des_limites_incoherentes_sont_refusees() throws Exception {
        mvc.perform(post("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, admin(alpha))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Dollars","type":"CURRENT","currency":"USD",
                                 "perTransactionLimitCents":100,"dailyLimitCents":100}"""))
                .andExpect(status().isBadRequest());
        open(admin(alpha), "Incohérent", 5_000, 1_000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Limites invalides"));
        open(admin(alpha), "Négatif", -1, 1_000).andExpect(status().isBadRequest());
    }

    @Test
    void une_organisation_ne_voit_pas_les_comptes_d_une_autre() throws Exception {
        String alphaAccount = idOf(open(admin(alpha), "Alpha", 1_000, 1_000));
        String betaAccount = idOf(open(admin(beta), "Beta", 1_000, 1_000));

        mvc.perform(get("/api/v1/accounts/" + betaAccount).header(HttpHeaders.AUTHORIZATION, admin(alpha)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/accounts?size=100").header(HttpHeaders.AUTHORIZATION, admin(alpha)))
                .andExpect(jsonPath("$.content[*].id", hasItem(alphaAccount)))
                .andExpect(jsonPath("$.content[*].id", everyItem(not(is(betaAccount)))));
        mvc.perform(post("/api/v1/accounts/" + betaAccount + "/freeze").header(HttpHeaders.AUTHORIZATION, admin(alpha)))
                .andExpect(status().isNotFound());
    }

    @Test
    void le_cycle_de_vie_d_un_compte_respecte_ses_etats() throws Exception {
        String id = idOf(open(admin(alpha), "Cycle", 1_000, 2_000));
        String token = admin(alpha);

        action(id, "freeze", token).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FROZEN"));
        action(id, "freeze", token).andExpect(status().isConflict());
        action(id, "unfreeze", token).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(put("/api/v1/accounts/" + id + "/limits").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perTransactionLimitCents\":3000,\"dailyLimitCents\":9000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limits.dailyCents").value(9000));
        action(id, "close", token).andExpect(jsonPath("$.status").value("CLOSED"));
        action(id, "unfreeze", token).andExpect(status().isConflict());
        action(id, "close", token).andExpect(status().isConflict());
    }

    @Test
    void la_liste_se_filtre_par_statut_et_se_pagine() throws Exception {
        UUID gamma = UUID.randomUUID();
        String token = admin(gamma);
        for (int i = 0; i < 3; i++) {
            idOf(open(token, "Compte " + i, 1_000, 1_000));
        }
        action(idOf(open(token, "Gelé", 1_000, 1_000)), "freeze", token);

        mvc.perform(get("/api/v1/accounts?size=2").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content.length()").value(2));
        mvc.perform(get("/api/v1/accounts?status=FROZEN").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Gelé"));
        mvc.perform(get("/api/v1/accounts?size=1000").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sans_jeton_ou_sans_organisation_l_acces_est_refuse() throws Exception {
        mvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + ISSUER.token().roles("tenant-admin").build()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Organisation absente"));
    }

    private ResultActions open(String token, String name, long perTransaction, long daily) throws Exception {
        return mvc.perform(post("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"%s","type":"CURRENT","perTransactionLimitCents":%d,"dailyLimitCents":%d}"""
                        .formatted(name, perTransaction, daily)));
    }

    private ResultActions action(String id, String action, String token) throws Exception {
        return mvc.perform(post("/api/v1/accounts/" + id + "/" + action).header(HttpHeaders.AUTHORIZATION, token));
    }

    private static String idOf(ResultActions created) throws Exception {
        String location = created.andExpect(status().isCreated()).andReturn().getResponse()
                .getHeader(HttpHeaders.LOCATION);
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private static String admin(UUID tenant) {
        return "Bearer " + ISSUER.token().tenant(tenant).roles("tenant-admin").build();
    }

    private static String member(UUID tenant) {
        return "Bearer " + ISSUER.token().tenant(tenant).roles("tenant-member").build();
    }
}
