package com.finflow.testing;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Faux Keycloak pour les tests : publie un JWKS sur un port local et signe des jetons qui ont
 * la même forme que ceux du realm {@code finflow} (émetteur, audience, {@code realm_access},
 * claim {@code tenant_id}).
 *
 * <p>Les services de test le branchent par {@code spring.security.oauth2.resourceserver.jwt.*} :
 * c'est la configuration de production qui valide les jetons (signature, émetteur, audience,
 * expiration), sans décodeur factice.
 */
public final class TestIssuer {

    public static final String AUDIENCE = "finflow-api";
    private static final String REALM_PATH = "/realms/finflow";
    private static final String JWKS_PATH = REALM_PATH + "/protocol/openid-connect/certs";

    private static TestIssuer shared;

    private final RSAKey signingKey;
    private final RSAKey unknownKey;
    private final String baseUrl;

    private TestIssuer() {
        this.signingKey = generateKey("finflow-test");
        this.unknownKey = generateKey("inconnue");
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext(JWKS_PATH, exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(jwks);
                }
            });
            server.start();
            this.baseUrl = "http://localhost:" + server.getAddress().getPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Une seule instance par JVM : le contexte Spring mis en cache garde la même adresse JWKS. */
    public static synchronized TestIssuer shared() {
        if (shared == null) {
            shared = new TestIssuer();
        }
        return shared;
    }

    public String issuer() {
        return baseUrl + REALM_PATH;
    }

    public String jwkSetUri() {
        return baseUrl + JWKS_PATH;
    }

    public TokenBuilder token() {
        return new TokenBuilder();
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public final class TokenBuilder {

        private String subject = UUID.randomUUID().toString();
        private String tenant;
        private final List<String> roles = new ArrayList<>();
        private List<String> audiences = List.of(AUDIENCE);
        private String issuer = TestIssuer.this.issuer();
        private Duration validity = Duration.ofMinutes(5);
        private boolean unknownSigner;

        public TokenBuilder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public TokenBuilder tenant(Object tenant) {
            this.tenant = String.valueOf(tenant);
            return this;
        }

        public TokenBuilder roles(String... roles) {
            this.roles.addAll(List.of(roles));
            return this;
        }

        public TokenBuilder audience(String... audiences) {
            this.audiences = List.of(audiences);
            return this;
        }

        public TokenBuilder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        /** Durée de validité ; une durée négative produit un jeton déjà expiré. */
        public TokenBuilder validFor(Duration validity) {
            this.validity = validity;
            return this;
        }

        /** Signe avec une clé absente du JWKS, comme un jeton forgé. */
        public TokenBuilder signedByUnknownKey() {
            this.unknownSigner = true;
            return this;
        }

        public String build() {
            Instant now = Instant.now();
            Instant expiry = now.plus(validity);
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(subject)
                    .audience(audiences)
                    .issueTime(Date.from(validity.isNegative() ? expiry.minusSeconds(60) : now))
                    .expirationTime(Date.from(expiry))
                    .jwtID(UUID.randomUUID().toString())
                    .claim("azp", "finflow-web")
                    .claim("realm_access", Map.of("roles", List.copyOf(roles)));
            if (tenant != null) {
                claims.claim("tenant_id", tenant);
            }
            RSAKey key = unknownSigner ? unknownKey : signingKey;
            try {
                SignedJWT jwt = new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
                jwt.sign(new RSASSASigner(key));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
