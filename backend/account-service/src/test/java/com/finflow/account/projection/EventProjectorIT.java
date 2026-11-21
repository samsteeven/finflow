package com.finflow.account.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finflow.account.PostgresIntegrationTest;
import com.finflow.events.EventEnvelope;
import com.finflow.events.PaymentChanged;
import com.finflow.events.PostedLine;
import com.finflow.events.Side;
import com.finflow.events.TransactionPosted;
import com.finflow.messaging.Envelopes;
import com.finflow.testing.ServiceDatabase;
import com.finflow.testing.TestIssuer;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Les projections d'Account : un effet par événement, jamais à reculons, jamais hors de l'organisation (ADR-014). */
@SpringBootTest
@AutoConfigureMockMvc
class EventProjectorIT extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired EventProjector projector;
    @Autowired ProjectionRepository repository;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    private UUID tenant;
    private String admin;
    private UUID account;
    private UUID other;

    @BeforeEach
    void deux_comptes() throws Exception {
        tenant = UUID.randomUUID();
        admin = "Bearer " + TestIssuer.shared().token().tenant(tenant).roles("tenant-admin").build();
        account = open("Courant");
        other = open("Épargne");
    }

    @Test
    void une_transaction_credite_et_debite_les_soldes_projetes() throws Exception {
        apply(transaction(tenant, UUID.randomUUID(), line(account, Side.CREDIT, 10_000), line(clearing(), Side.DEBIT, 10_000)));
        apply(transaction(tenant, UUID.randomUUID(), line(account, Side.DEBIT, 4_000), line(other, Side.CREDIT, 4_000)));

        assertThat(balance(account)).isEqualTo(6_000);
        assertThat(balance(other)).isEqualTo(4_000);
    }

    @Test
    void un_evenement_livre_deux_fois_n_a_qu_un_effet() throws Exception {
        EventEnvelope event = transaction(tenant, UUID.randomUUID(), line(account, Side.CREDIT, 5_000),
                line(clearing(), Side.DEBIT, 5_000));

        apply(event);
        apply(event);
        apply(event);

        assertThat(balance(account)).isEqualTo(5_000);
        assertThat(scalar("select count(*) from inbox where event_id = '" + event.getEventId() + "'")).isEqualTo(1);
    }

    @Test
    void un_evenement_ancien_arrive_apres_un_recent_ne_fait_pas_regresser_le_paiement() throws Exception {
        UUID payment = UUID.randomUUID();
        apply(payment(tenant, payment, 3, "COMPLETED"));
        apply(payment(tenant, payment, 2, "PROCESSING"));
        apply(payment(tenant, payment, 1, "CREATED"));

        mvc.perform(get("/api/v1/accounts/" + account + "/activity").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.payments[0].aggregateVersion").value(3));
    }

    @Test
    void les_versions_dans_l_ordre_font_avancer_la_projection() throws Exception {
        UUID payment = UUID.randomUUID();
        apply(payment(tenant, payment, 1, "CREATED"));
        apply(payment(tenant, payment, 2, "PROCESSING"));

        mvc.perform(get("/api/v1/accounts/" + account + "/activity").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.payments[0].status").value("PROCESSING"));
    }

    @Test
    void une_cle_de_partition_incoherente_est_refusee_sans_effet() throws Exception {
        EventEnvelope event = transaction(tenant, UUID.randomUUID(), line(account, Side.CREDIT, 5_000),
                line(clearing(), Side.DEBIT, 5_000));

        assertThatThrownBy(() -> projector.apply("autre-cle", event)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projector.apply(null, event)).isInstanceOf(IllegalArgumentException.class);

        assertThat(balance(account)).isZero();
    }

    @Test
    void un_evenement_d_une_autre_organisation_ne_touche_pas_ce_compte() throws Exception {
        UUID intruder = UUID.randomUUID();
        // L'organisation « intruder » désigne le compte d'une autre : la RLS ne le lui montre pas.
        apply(transaction(intruder, UUID.randomUUID(), line(account, Side.CREDIT, 9_000), line(clearing(), Side.DEBIT, 9_000)));

        assertThat(balance(account)).isZero();
        assertThat(scalar("select count(*) from account_balance_projection where account_id = '" + account + "'"))
                .isZero();
    }

    @Test
    void les_projections_ne_se_lisent_pas_depuis_une_autre_organisation() throws Exception {
        apply(payment(tenant, UUID.randomUUID(), 1, "CREATED"));
        apply(transaction(tenant, UUID.randomUUID(), line(account, Side.CREDIT, 1_000), line(clearing(), Side.DEBIT, 1_000)));
        var stranger = com.finflow.security.TenantId.of(UUID.randomUUID());

        var payments = com.finflow.security.TenantContext.callAs(stranger, () -> tx.execute(s -> repository.payments(account, 20)));
        var balance = com.finflow.security.TenantContext.callAs(stranger, () -> tx.execute(s -> repository.balance(account)));
        var own = com.finflow.security.TenantContext.callAs(com.finflow.security.TenantId.of(tenant),
                () -> tx.execute(s -> repository.payments(account, 20)));

        assertThat(payments).isEmpty();
        assertThat(balance).isEmpty();
        assertThat(own).hasSize(1);
    }

    @Test
    void un_compte_inconnu_comme_la_compensation_du_ledger_est_ignore() throws Exception {
        apply(transaction(tenant, UUID.randomUUID(), line(account, Side.CREDIT, 1_000), line(clearing(), Side.DEBIT, 1_000)));

        assertThat(scalar("select count(*) from account_balance_projection where tenant_id = '" + tenant + "'"))
                .isEqualTo(1);
    }

    @Test
    void un_echec_annule_l_effet_et_la_marque_donc_l_evenement_sera_retraite() throws Exception {
        UUID payment = UUID.randomUUID();
        EventEnvelope broken = event(tenant, payment, 1, PaymentChanged.newBuilder().setStatus("CREATED")
                .setSourceAccountId("pas-un-uuid").setDestinationAccountId(other.toString()).setAmountCents(100)
                .setCurrency("EUR").build());

        assertThatThrownBy(() -> apply(broken)).isInstanceOf(IllegalArgumentException.class);

        assertThat(scalar("select count(*) from inbox where event_id = '" + broken.getEventId() + "'")).isZero();
    }

    @Test
    void l_activite_d_un_compte_inconnu_ou_d_une_autre_organisation_est_introuvable() throws Exception {
        mvc.perform(get("/api/v1/accounts/" + UUID.randomUUID() + "/activity").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());
        String stranger = "Bearer " + TestIssuer.shared().token().tenant(UUID.randomUUID()).roles("tenant-admin").build();
        mvc.perform(get("/api/v1/accounts/" + account + "/activity").header(HttpHeaders.AUTHORIZATION, stranger))
                .andExpect(status().isNotFound());
    }

    // --- aides -----------------------------------------------------------------------------------

    private void apply(EventEnvelope event) {
        projector.apply(Envelopes.partitionKey(event), event);
    }

    private static UUID clearing() {
        return UUID.randomUUID();
    }

    private long balance(UUID id) throws Exception {
        String json = mvc.perform(get("/api/v1/accounts/" + id + "/activity").header(HttpHeaders.AUTHORIZATION, admin))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.projectedBalanceCents")).longValue();
    }

    private EventEnvelope payment(UUID tenantId, UUID payment, long version, String status) {
        return event(tenantId, payment, version, PaymentChanged.newBuilder().setStatus(status)
                .setSourceAccountId(account.toString()).setDestinationAccountId(other.toString()).setAmountCents(100)
                .setCurrency("EUR").build());
    }

    private static EventEnvelope event(UUID tenantId, UUID aggregate, long version, Object payload) {
        return Envelopes.of(UUID.randomUUID(), "test", "PaymentChanged", aggregate, version, tenantId, "corr", null,
                Instant.now(), payload);
    }

    private static EventEnvelope transaction(UUID tenantId, UUID id, PostedLine... lines) {
        return Envelopes.of(UUID.randomUUID(), "ledger-service", "TransactionPosted", id, 1, tenantId, null, null,
                Instant.now(), TransactionPosted.newBuilder().setTransactionId(id.toString()).setKind("TRANSFER")
                        .setLines(List.of(lines)).build());
    }

    private static PostedLine line(UUID account, Side side, long cents) {
        return PostedLine.newBuilder().setAccountId(account.toString()).setSide(side).setAmountCents(cents).build();
    }

    private UUID open(String name) throws Exception {
        String json = mvc.perform(post("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"%s","type":"CURRENT","perTransactionLimitCents":100000,"dailyLimitCents":500000}"""
                                .formatted(name)))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private static long scalar(String sql) throws Exception {
        try (Connection c = ServiceDatabase.of("account").superuserConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
