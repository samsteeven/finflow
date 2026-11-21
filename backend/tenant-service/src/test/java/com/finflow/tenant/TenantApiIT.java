package com.finflow.tenant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finflow.testing.TestIssuer;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** L'API de bout en bout : vrais jetons signés, validés par la configuration de production. */
@SpringBootTest
@AutoConfigureMockMvc
class TenantApiIT extends PostgresIntegrationTest {

    private static final TestIssuer ISSUER = TestIssuer.shared();

    @Autowired MockMvc mvc;

    @Test
    void sans_jeton_la_requete_est_refusee() throws Exception {
        mvc.perform(get("/api/v1/tenants/current")).andExpect(status().isUnauthorized());
    }

    @Test
    void un_jeton_forge_expire_ou_destine_a_un_autre_public_est_refuse() throws Exception {
        UUID tenant = UUID.randomUUID();
        for (String token : new String[] {
                ISSUER.token().tenant(tenant).signedByUnknownKey().build(),
                ISSUER.token().tenant(tenant).validFor(Duration.ofMinutes(-5)).build(),
                ISSUER.token().tenant(tenant).audience("autre-api").build(),
                ISSUER.token().tenant(tenant).issuer("http://localhost:1/realms/pirate").build()}) {
            mvc.perform(get("/api/v1/tenants/current").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void la_sante_du_service_est_publique() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void un_administrateur_de_plateforme_cree_une_organisation_que_son_administrateur_peut_lire() throws Exception {
        String slug = slug("alpha");
        String location = createTenant("Alpha SAS", slug, "admin-alpha")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/v1/tenants/")))
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        String id = location.substring(location.lastIndexOf('/') + 1);

        String adminToken = ISSUER.token().subject("admin-alpha").tenant(id).roles("tenant-admin").build();
        mvc.perform(get("/api/v1/tenants/current").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Alpha SAS"));
        mvc.perform(get("/api/v1/tenants/current/members").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].userId").value("admin-alpha"))
                .andExpect(jsonPath("$[0].role").value("TENANT_ADMIN"));
    }

    @Test
    void seul_un_administrateur_de_plateforme_cree_une_organisation() throws Exception {
        String token = ISSUER.token().tenant(UUID.randomUUID()).roles("tenant-admin").build();
        mvc.perform(post("/api/v1/tenants").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Intrus", slug("intrus"), "x")))
                .andExpect(status().isForbidden());
    }

    @Test
    void une_demande_invalide_est_decrite_en_problem_details() throws Exception {
        createTenant("", "Pas Valide", "admin")
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("application/problem+json")));
    }

    @Test
    void un_identifiant_court_deja_pris_donne_un_conflit() throws Exception {
        String slug = slug("doublon");
        createTenant("Premier", slug, "a").andExpect(status().isCreated());
        createTenant("Second", slug, "b")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Identifiant court déjà utilisé"));
    }

    @Test
    void une_organisation_ne_voit_ni_les_donnees_ni_l_existence_d_une_autre() throws Exception {
        String alpha = idOf(createTenant("Alpha", slug("alpha"), "admin-a"));
        String beta = idOf(createTenant("Beta", slug("beta"), "admin-b"));
        String alphaToken = ISSUER.token().tenant(alpha).roles("tenant-admin").build();

        mvc.perform(get("/api/v1/tenants/" + beta).header(HttpHeaders.AUTHORIZATION, "Bearer " + alphaToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/tenants/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, "Bearer " + alphaToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/tenants/" + alpha).header(HttpHeaders.AUTHORIZATION, "Bearer " + alphaToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tenants/current/members").header(HttpHeaders.AUTHORIZATION, "Bearer " + alphaToken))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].userId").value("admin-a"));
    }

    @Test
    void un_en_tete_de_tenant_envoye_par_le_client_est_ignore() throws Exception {
        String alpha = idOf(createTenant("Alpha", slug("alpha"), "admin-a"));
        String beta = idOf(createTenant("Beta", slug("beta"), "admin-b"));

        mvc.perform(get("/api/v1/tenants/current")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ISSUER.token().tenant(alpha).build())
                        .header("X-Tenant-Id", beta)
                        .param("tenantId", beta))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alpha));
    }

    @Test
    void un_utilisateur_sans_organisation_est_refuse() throws Exception {
        mvc.perform(get("/api/v1/tenants/current")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ISSUER.token().roles("tenant-member").build()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Organisation absente"));
    }

    @Test
    void un_simple_membre_ne_liste_pas_les_membres() throws Exception {
        String alpha = idOf(createTenant("Alpha", slug("alpha"), "admin-a"));
        mvc.perform(get("/api/v1/tenants/current/members").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + ISSUER.token().tenant(alpha).roles("tenant-member").build()))
                .andExpect(status().isForbidden());
    }

    private ResultActions createTenant(String name, String slug, String adminUserId) throws Exception {
        String token = ISSUER.token().roles("platform-admin").build();
        return mvc.perform(post("/api/v1/tenants").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(name, slug, adminUserId)));
    }

    private static String idOf(ResultActions created) throws Exception {
        String location = created.andExpect(status().isCreated()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private static String body(String name, String slug, String adminUserId) {
        return """
                {"name": "%s", "slug": "%s", "plan": "SANDBOX", "adminUserId": "%s"}
                """.formatted(name, slug, adminUserId);
    }

    private static String slug(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
