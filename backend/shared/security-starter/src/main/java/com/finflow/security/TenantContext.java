package com.finflow.security;

import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Tenant courant de la requête ou du traitement.
 *
 * <p>Ordre de résolution : un tenant déclaré explicitement par un traitement système
 * ({@link #callAs}, {@link #runAs}), sinon la claim {@value #TENANT_CLAIM} du jeton validé.
 * Aucune autre source n'est lue : un en-tête ou un paramètre envoyé par le client n'a aucun effet.
 */
public final class TenantContext {

    public static final String TENANT_CLAIM = "tenant_id";

    private static final ThreadLocal<TenantId> OVERRIDE = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<TenantId> current() {
        TenantId explicit = OVERRIDE.get();
        if (explicit != null) {
            return Optional.of(explicit);
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return TenantId.parse(jwt.getToken().getClaimAsString(TENANT_CLAIM));
        }
        return Optional.empty();
    }

    /** Tenant obligatoire : son absence est un refus d'accès, pas une erreur serveur. */
    public static TenantId require() {
        return current().orElseThrow(MissingTenantException::new);
    }

    /** Exécute {@code work} pour le compte de {@code tenant}, puis restaure le contexte précédent. */
    public static <T> T callAs(TenantId tenant, Supplier<T> work) {
        TenantId previous = OVERRIDE.get();
        OVERRIDE.set(tenant);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                OVERRIDE.remove();
            } else {
                OVERRIDE.set(previous);
            }
        }
    }

    public static void runAs(TenantId tenant, Runnable work) {
        callAs(tenant, () -> {
            work.run();
            return null;
        });
    }
}
