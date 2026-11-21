package com.finflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TenantContextTest {

    private static final UUID ALPHA = UUID.fromString("a1a1a1a1-0000-4000-8000-000000000001");
    private static final UUID BETA = UUID.fromString("b2b2b2b2-0000-4000-8000-000000000002");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void le_tenant_vient_de_la_claim_du_jeton() {
        authenticate(Map.of("tenant_id", ALPHA.toString()));

        assertThat(TenantContext.current()).contains(TenantId.of(ALPHA));
    }

    @Test
    void une_claim_absente_ou_mal_formee_ne_donne_aucun_tenant() {
        authenticate(Map.of());
        assertThat(TenantContext.current()).isEmpty();
        assertThatThrownBy(TenantContext::require).isInstanceOf(MissingTenantException.class);

        authenticate(Map.of("tenant_id", "pas-un-uuid"));
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void une_authentification_qui_n_est_pas_un_jeton_ne_donne_aucun_tenant() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p"));

        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void callAs_prend_le_pas_sur_le_jeton_puis_restaure_le_contexte() {
        authenticate(Map.of("tenant_id", ALPHA.toString()));

        TenantId inside = TenantContext.callAs(TenantId.of(BETA), () -> {
            TenantId nested = TenantContext.callAs(TenantId.of(ALPHA), TenantContext::require);
            assertThat(nested.value()).isEqualTo(ALPHA);
            return TenantContext.require();
        });

        assertThat(inside.value()).isEqualTo(BETA);
        assertThat(TenantContext.require().value()).isEqualTo(ALPHA);
    }

    @Test
    void runAs_restaure_le_contexte_meme_en_cas_d_exception() {
        assertThatThrownBy(() -> TenantContext.runAs(TenantId.of(BETA), () -> {
            throw new IllegalStateException("échec");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(TenantContext.current()).isEmpty();
    }

    private static void authenticate(Map<String, Object> claims) {
        var jwt = KeycloakJwtAuthenticationConverterTest.jwt(claims);
        SecurityContextHolder.getContext().setAuthentication(new KeycloakJwtAuthenticationConverter().convert(jwt));
    }
}
