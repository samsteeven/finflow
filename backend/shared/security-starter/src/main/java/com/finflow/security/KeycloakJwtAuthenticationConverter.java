package com.finflow.security;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Transforme un jeton d'accès Keycloak en authentification Spring Security.
 *
 * <p>Les rôles du realm ({@code realm_access.roles}) deviennent des autorités {@code ROLE_*}
 * en majuscules ({@code tenant-admin} → {@code ROLE_TENANT_ADMIN}). Les rôles techniques de
 * Keycloak ({@code offline_access}, {@code uma_authorization}, {@code default-roles-*}) sont
 * ignorés. Le nom de l'authentification est le {@code sub} du jeton.
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    public static final String TENANT_MEMBER = "TENANT_MEMBER";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, authorities(jwt), jwt.getSubject());
    }

    static Collection<GrantedAuthority> authorities(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(role -> !role.equals("offline_access")
                        && !role.equals("uma_authorization")
                        && !role.startsWith("default-roles-"))
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(
                        "ROLE_" + role.toUpperCase(Locale.ROOT).replace('-', '_')))
                .toList();
    }
}
