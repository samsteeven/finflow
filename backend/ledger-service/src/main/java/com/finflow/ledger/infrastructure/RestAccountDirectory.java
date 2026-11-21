package com.finflow.ledger.infrastructure;

import com.finflow.ledger.application.AccountDirectory;
import com.finflow.ledger.application.AccountServiceUnavailableException;
import com.finflow.ledger.application.AccountView;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Appel synchrone à account-service en relayant le jeton de l'utilisateur (ADR-010) : Account
 * applique son propre contrôle d'accès et sa RLS, Ledger n'a aucun droit propre sur les comptes.
 */
@Component
class RestAccountDirectory implements AccountDirectory {

    private final RestClient client;

    RestAccountDirectory(RestClient.Builder builder,
                         @Value("${finflow.accounts.base-url:http://localhost:8082}") String baseUrl,
                         @Value("${finflow.accounts.connect-timeout:1s}") Duration connectTimeout,
                         @Value("${finflow.accounts.read-timeout:2s}") Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Optional<AccountView> find(UUID accountId) {
        try {
            return client.get()
                    .uri("/api/v1/accounts/{id}", accountId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerToken())
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                            return Optional.<AccountView>empty();
                        }
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return Optional.ofNullable(response.bodyTo(AccountView.class));
                        }
                        throw new AccountServiceUnavailableException(
                                "account-service a répondu " + response.getStatusCode().value(), null);
                    });
        } catch (RestClientException e) {
            throw new AccountServiceUnavailableException("account-service injoignable", e);
        }
    }

    private static String callerToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getTokenValue();
        }
        throw new IllegalStateException("Aucun jeton à relayer : l'appel doit venir d'une requête authentifiée");
    }
}
