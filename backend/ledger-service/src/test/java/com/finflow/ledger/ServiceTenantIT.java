package com.finflow.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * Délégation d'organisation pour le compte de service (ADR-015) : l'en-tête n'a d'effet que pour
 * un jeton de service sans organisation, et seulement sur les routes internes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceTenantIT extends LedgerIntegrationTest {

    @Autowired MockMvc mvc;

    private UUID alpha;
    private UUID beta;
    private UUID a;
    private UUID b;
    private String service;

    @BeforeEach
    void comptes_d_alpha_finances() throws Exception {
        ACCOUNTS.down(false);
        alpha = UUID.randomUUID();
        beta = UUID.randomUUID();
        a = ACCOUNTS.add(alpha, "ACTIVE", 200_000, 1_000_000);
        b = ACCOUNTS.add(alpha, "ACTIVE", 200_000, 1_000_000);
        service = "Bearer " + ISSUER.token().roles("service").build();
        mvc.perform(post("/api/v1/ledger/deposits").header(HttpHeaders.AUTHORIZATION, bearer(alpha, "tenant-admin"))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"amountCents\":50000}".formatted(a)))
                .andExpect(status().isCreated());
    }

    @Test
    void un_jeton_de_service_agit_dans_l_organisation_designee() throws Exception {
        transfer(service, alpha.toString(), a, b, 7_000).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/ledger/accounts/" + b + "/balance")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alpha, "tenant-admin")))
                .andExpect(jsonPath("$.balanceCents").value(7_000));
    }

    @Test
    void sans_en_tete_d_organisation_le_jeton_de_service_est_refuse() throws Exception {
        transfer(service, null, a, b, 7_000).andExpect(status().isBadRequest());
    }

    @Test
    void l_organisation_designee_borne_ce_que_le_service_voit() throws Exception {
        transfer(service, beta.toString(), a, b, 7_000).andExpect(status().isNotFound());
    }

    @Test
    void un_en_tete_forge_par_un_utilisateur_n_a_aucun_effet() throws Exception {
        // Bruno (organisation beta) désigne alpha : l'en-tête est ignoré, il reste dans beta.
        transfer(bearer(beta, "tenant-admin"), alpha.toString(), a, b, 7_000).andExpect(status().isNotFound());
    }

    @Test
    void un_jeton_de_service_n_a_pas_acces_a_l_api_publique() throws Exception {
        mvc.perform(get("/api/v1/ledger/accounts/" + a + "/balance").header(HttpHeaders.AUTHORIZATION, service)
                        .header("X-Tenant-Id", alpha.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void un_jeton_de_service_qui_porte_une_organisation_ne_peut_pas_en_changer() throws Exception {
        String hybrid = "Bearer " + ISSUER.token().tenant(beta).roles("service").build();
        transfer(hybrid, alpha.toString(), a, b, 7_000).andExpect(status().isNotFound());
    }

    private ResultActions transfer(String token, String tenantHeader, UUID source, UUID destination, long cents)
            throws Exception {
        var request = post("/internal/v1/ledger/transfers").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(
                        "{\"paymentId\":\"%s\",\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":%d}"
                                .formatted(UUID.randomUUID(), source, destination, cents));
        if (tenantHeader != null) {
            request.header("X-Tenant-Id", tenantHeader);
        }
        return mvc.perform(request);
    }
}
