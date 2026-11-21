package com.finflow.payment;

import com.finflow.testing.ServiceDatabase;
import com.finflow.testing.TestIssuer;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Vrai PostgreSQL (rôle applicatif), faux Keycloak et faux account-service. */
public abstract class PaymentIntegrationTest {

    static final ServiceDatabase DATABASE = ServiceDatabase.of("payment");
    static final FakeAccountService ACCOUNTS = new FakeAccountService();
    static final FakeLedgerService LEDGER = new FakeLedgerService();
    static final TestIssuer ISSUER = TestIssuer.shared();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DATABASE.register(registry);
        registry.add("finflow.accounts.base-url", ACCOUNTS::baseUrl);
        registry.add("finflow.ledger.base-url", LEDGER::baseUrl);
    }

    @BeforeEach
    void accountServiceUp() {
        ACCOUNTS.down(false);
        LEDGER.reset();
    }

    static String bearer(UUID tenant, String role) {
        return "Bearer " + ISSUER.token().tenant(tenant).roles(role).build();
    }

    static String key() {
        return "cle-" + UUID.randomUUID();
    }
}
