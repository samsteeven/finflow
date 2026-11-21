package com.finflow.payment.infrastructure;

import com.finflow.payment.application.LedgerGateway;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Appel synchrone au Ledger en relayant le jeton de l'utilisateur (ADR-012). Les erreurs sont
 * classées en trois issues : comptabilisé, refus métier, ou inconnu. Seul un refus portant un
 * code connu clôt le paiement ; tout le reste se rejoue avec la même clé, sans double écriture.
 */
@Component
class RestLedgerGateway implements LedgerGateway {

    private final RestClient client;

    RestLedgerGateway(RestClient.Builder builder,
                      @Value("${finflow.ledger.base-url:http://localhost:8084}") String baseUrl,
                      @Value("${finflow.ledger.connect-timeout:1s}") Duration connectTimeout,
                      @Value("${finflow.ledger.read-timeout:3s}") Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Outcome transfer(UUID paymentId, UUID source, UUID destination, long amountCents, String description) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("paymentId", paymentId, "sourceAccountId", source,
                "destinationAccountId", destination, "amountCents", amountCents));
        if (description != null) {
            body.put("description", description);
        }
        return post("/internal/v1/ledger/transfers", body);
    }

    @Override
    public Outcome reverse(UUID refundId, UUID ledgerTransactionId, String description) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("refundId", refundId,
                "transactionId", ledgerTransactionId));
        if (description != null) {
            body.put("description", description);
        }
        return post("/internal/v1/ledger/reversals", body);
    }

    private Outcome post(String path, Map<String, Object> body) {
        try {
            return client.post().uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerToken())
                    .body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        Map<String, Object> payload = readBody(response);
                        if (status == 200 || status == 201) {
                            Object id = payload == null ? null : payload.get("id");
                            return id == null ? new Outcome.Unavailable("réponse du Ledger sans identifiant")
                                    : new Outcome.Posted(UUID.fromString(id.toString()));
                        }
                        Object code = payload == null ? null : payload.get("code");
                        if (code != null && isBusinessRefusal(status, code.toString())) {
                            Object detail = payload.get("detail");
                            return new Outcome.Refused(code.toString(), detail == null ? "" : detail.toString());
                        }
                        return new Outcome.Unavailable("le Ledger a répondu " + status
                                + (code == null ? "" : " (" + code + ")"));
                    });
        } catch (RestClientException e) {
            return new Outcome.Unavailable("Ledger injoignable : " + e.getMessage());
        }
    }

    /** Les seuls refus qui closent un paiement : l'argent manque, ou la transaction n'est plus contrepassable. */
    private static boolean isBusinessRefusal(int status, String code) {
        return (status == 422 && "FONDS_INSUFFISANTS".equals(code))
                || (status == 409 && "NON_CONTREPASSABLE".equals(code));
    }

    private static Map<String, Object> readBody(org.springframework.http.client.ClientHttpResponse response) {
        try {
            return new tools.jackson.databind.json.JsonMapper().readValue(response.getBody(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return null;
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
