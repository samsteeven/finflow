package com.finflow.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakJwtAuthenticationConverterTest {

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter();

    @Test
    void les_roles_du_realm_deviennent_des_autorites_en_majuscules() {
        var auth = converter.convert(jwt(Map.of("realm_access",
                Map.of("roles", List.of("tenant-admin", "tenant-member")))));

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_TENANT_ADMIN", "ROLE_TENANT_MEMBER");
        assertThat(auth.getName()).isEqualTo("user-1");
    }

    @Test
    void les_roles_techniques_de_keycloak_sont_ignores() {
        var auth = converter.convert(jwt(Map.of("realm_access", Map.of("roles",
                List.of("offline_access", "uma_authorization", "default-roles-finflow", "platform-admin")))));

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_PLATFORM_ADMIN");
    }

    @Test
    void un_jeton_sans_roles_ne_donne_aucune_autorite() {
        assertThat(converter.convert(jwt(Map.of())).getAuthorities()).isEmpty();
        assertThat(converter.convert(jwt(Map.of("realm_access", Map.of("roles", "admin")))).getAuthorities())
                .isEmpty();
    }

    static Jwt jwt(Map<String, Object> claims) {
        var builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("user-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
