package com.finflow.ledger.config;

import com.finflow.security.TenantContext;
import com.finflow.security.TenantId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Délégation d'organisation pour un compte de service (ADR-015).
 *
 * <p>Le tenant vient toujours du jeton, sauf pour un jeton technique : il porte le rôle
 * {@code service}, aucune organisation, et l'organisation traitée est alors celle de
 * l'en-tête {@code X-Tenant-Id}. L'en-tête n'a aucun effet pour tout autre appelant, et la
 * Gateway ne route pas {@code /internal/**} : un client externe ne peut pas l'atteindre.
 */
class ServiceTenantFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Tenant-Id";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !isService(jwt)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<TenantId> tenant = TenantId.parse(request.getHeader(HEADER));
        if (tenant.isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "En-tête " + HEADER + " obligatoire");
            return;
        }
        AtomicReference<Exception> failure = new AtomicReference<>();
        TenantContext.runAs(tenant.get(), () -> {
            try {
                chain.doFilter(request, response);
            } catch (IOException | ServletException e) {
                failure.set(e);
            }
        });
        if (failure.get() instanceof IOException e) {
            throw e;
        }
        if (failure.get() instanceof ServletException e) {
            throw e;
        }
    }

    private static boolean isService(JwtAuthenticationToken jwt) {
        boolean hasRole = jwt.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SERVICE"));
        // Un jeton qui porte déjà une organisation n'a pas besoin de délégation, et ne peut pas en changer.
        return hasRole && jwt.getToken().getClaimAsString(TenantContext.TENANT_CLAIM) == null;
    }
}
