package com.finflow.payment.infrastructure;

import com.finflow.payment.application.ServiceTokenProvider;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Jeton {@code client_credentials} du client Keycloak {@code payment-service}, gardé en mémoire
 * jusqu'à peu avant son expiration. Le secret vient de la configuration, jamais du code.
 */
@Component
class KeycloakServiceTokenProvider implements ServiceTokenProvider {

    private static final Duration MARGIN = Duration.ofSeconds(30);

    private final RestClient client;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;
    private String cached;
    private Instant expiresAt = Instant.MIN;

    KeycloakServiceTokenProvider(RestClient.Builder builder, Clock clock,
                                 @Value("${finflow.service-account.token-uri}") String tokenUri,
                                 @Value("${finflow.service-account.client-id}") String clientId,
                                 @Value("${finflow.service-account.client-secret}") String clientSecret) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = builder.requestFactory(factory).build();
        this.clock = clock;
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public synchronized String token() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(expiresAt.minus(MARGIN))) {
            return cached;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        try {
            Map<?, ?> response = client.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(Map.class);
            Object token = response == null ? null : response.get("access_token");
            Object lifetime = response == null ? null : response.get("expires_in");
            if (token == null) {
                throw new IllegalStateException("Réponse de Keycloak sans jeton");
            }
            cached = token.toString();
            expiresAt = now.plusSeconds(lifetime instanceof Number n ? n.longValue() : 60);
            return cached;
        } catch (RestClientException e) {
            throw new IllegalStateException("Jeton de service indisponible : " + e.getMessage(), e);
        }
    }
}
