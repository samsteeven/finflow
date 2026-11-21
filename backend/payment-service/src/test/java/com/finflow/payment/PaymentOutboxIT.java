package com.finflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.finflow.events.EventEnvelope;
import com.finflow.events.PaymentChanged;
import com.finflow.messaging.AvroJson;
import com.finflow.payment.application.PaymentEventStore;
import com.finflow.payment.domain.Payment;
import com.finflow.security.TenantContext;
import com.finflow.security.TenantId;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** Chaque événement de l'event store a son événement d'outbox, écrit dans la même transaction (ADR-013). */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentOutboxIT extends PaymentIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired PaymentEventStore store;
    @Autowired TransactionTemplate tx;

    private final UUID tenant = UUID.randomUUID();

    @Test
    void un_paiement_termine_publie_ses_quatre_evenements_dans_l_ordre() throws Exception {
        UUID a = ACCOUNTS.add(tenant, "ACTIVE", 200_000, 1_000_000);
        UUID b = ACCOUNTS.add(tenant, "ACTIVE", 200_000, 1_000_000);
        String id = pay(a, b, 15_000, "corr-outbox");

        List<EventEnvelope> events = eventsOf(id);
        assertThat(events).extracting(EventEnvelope::getEventType).containsExactly("PaymentCreated",
                "PaymentValidationStarted", "PaymentValidated", "PaymentCompleted");
        assertThat(events).extracting(EventEnvelope::getAggregateVersion).containsExactly(1L, 2L, 3L, 4L);
        assertThat(events).extracting(e -> ((PaymentChanged) e.getPayload()).getStatus())
                .containsExactly("CREATED", "VALIDATING", "PROCESSING", "COMPLETED");
        assertThat(events).extracting(EventEnvelope::getTenantId).containsOnly(tenant.toString());
        assertThat(events).extracting(EventEnvelope::getProducer).containsOnly("payment-service");
        assertThat(events).extracting(EventEnvelope::getCorrelationId).containsOnly("corr-outbox");
        // La chaîne de causalité : chaque événement a pour cause le précédent.
        assertThat(events.get(0).getCausationId()).isNull();
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).getCausationId()).isEqualTo(events.get(i - 1).getEventId());
        }
        PaymentChanged last = (PaymentChanged) events.getLast().getPayload();
        assertThat(last.getAmountCents()).isEqualTo(15_000);
        assertThat(last.getSourceAccountId()).isEqualTo(a.toString());
        assertThat(last.getLedgerTransactionId()).isNotNull();
        // Même identifiant que dans l'event store : on retrouve l'un depuis l'autre.
        assertThat(events).extracting(EventEnvelope::getEventId).containsExactlyElementsOf(storedEventIds(id));
        assertThat(keysOf(id)).containsOnly(tenant + ":" + id);
    }

    @Test
    void un_refus_comptable_publie_le_motif() throws Exception {
        UUID a = ACCOUNTS.add(tenant, "ACTIVE", 200_000, 1_000_000);
        UUID b = ACCOUNTS.add(tenant, "ACTIVE", 200_000, 1_000_000);
        LEDGER.insufficientFunds(true);

        String id = pay(a, b, 15_000, "corr-refus");

        PaymentChanged last = (PaymentChanged) eventsOf(id).getLast().getPayload();
        assertThat(last.getStatus()).isEqualTo("REJECTED");
        assertThat(last.getReason()).isEqualTo("FONDS_INSUFFISANTS");
        assertThat(last.getLedgerTransactionId()).isNull();
    }

    @Test
    void un_remboursement_publie_ses_transitions() throws Exception {
        UUID a = ACCOUNTS.add(tenant, "ACTIVE", 200_000, 1_000_000);
        UUID b = ACCOUNTS.add(tenant, "ACTIVE", 200_000, 1_000_000);
        String id = pay(a, b, 15_000, "corr-remb");
        mvc.perform(post("/api/v1/payments/" + id + "/refunds").header(HttpHeaders.AUTHORIZATION,
                        bearer(tenant, "tenant-admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"erreur de saisie\"}"));

        assertThat(eventsOf(id)).extracting(e -> ((PaymentChanged) e.getPayload()).getStatus())
                .containsExactly("CREATED", "VALIDATING", "PROCESSING", "COMPLETED", "REFUND_PENDING", "REFUNDED");
    }

    @Test
    void un_echec_technique_puis_la_reprise_publient_FAILED_puis_PROCESSING() throws Exception {
        UUID a = ACCOUNTS.add(tenant, "ACTIVE", 200_000, 1_000_000);
        UUID b = ACCOUNTS.add(tenant, "ACTIVE", 200_000, 1_000_000);
        String key = key();
        LEDGER.down(true);
        String id = pay(key, a, b, 15_000, "corr-echec");
        assertThat(eventsOf(id)).extracting(e -> ((PaymentChanged) e.getPayload()).getStatus())
                .containsExactly("CREATED", "VALIDATING", "PROCESSING", "FAILED");
        assertThat(((PaymentChanged) eventsOf(id).getLast().getPayload()).getReason()).isNotBlank();

        LEDGER.down(false);
        pay(key, a, b, 15_000, "corr-echec");

        assertThat(eventsOf(id)).extracting(e -> ((PaymentChanged) e.getPayload()).getStatus())
                .containsExactly("CREATED", "VALIDATING", "PROCESSING", "FAILED", "PROCESSING", "COMPLETED");
        assertThat(eventsOf(id)).extracting(EventEnvelope::getEventType).containsSubsequence("PaymentFailed",
                "PaymentRetried", "PaymentCompleted");
    }

    @Test
    void une_transaction_annulee_n_ecrit_ni_evenement_ni_outbox() throws Exception {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.initiate(paymentId, tenant, UUID.randomUUID(), UUID.randomUUID(), 1_000, "EUR",
                null, "test", Instant.now());

        try {
            TenantContext.runAs(TenantId.of(tenant), () -> tx.executeWithoutResult(status -> {
                store.append(payment, "corr-annulee");
                throw new IllegalStateException("échec après l'écriture");
            }));
        } catch (IllegalStateException expected) {
            // attendu : la transaction est annulée
        }

        assertThat(eventsOf(paymentId.toString())).isEmpty();
        assertThat(scalar("select count(*) from payment_events where payment_id = '" + paymentId + "'")).isZero();
    }

    @Test
    void sans_transaction_l_ecriture_de_l_outbox_est_refusee() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.initiate(paymentId, tenant, UUID.randomUUID(), UUID.randomUUID(), 1_000, "EUR",
                null, "test", Instant.now());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        TenantContext.runAs(TenantId.of(tenant), () -> store.append(payment, "corr")))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    // --- aides -----------------------------------------------------------------------------------

    private String pay(UUID a, UUID b, long cents, String correlation) throws Exception {
        return pay(key(), a, b, cents, correlation);
    }

    private String pay(String idempotencyKey, UUID a, UUID b, long cents, String correlation) throws Exception {
        String response = mvc.perform(post("/api/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenant, "tenant-member"))
                        .header("Idempotency-Key", idempotencyKey).header("X-Correlation-Id", correlation)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s","amountCents":%d,"currency":"EUR"}"""
                                .formatted(a, b, cents)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private static List<EventEnvelope> eventsOf(String aggregateId) throws Exception {
        List<EventEnvelope> events = new ArrayList<>();
        try (Connection c = DATABASE.superuserConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select payload from outbox order by id")) {
            while (rs.next()) {
                EventEnvelope envelope = AvroJson.read(rs.getString(1));
                if (envelope.getAggregateId().equals(aggregateId)) {
                    events.add(envelope);
                }
            }
        }
        return events;
    }

    private static List<String> keysOf(String aggregateId) throws Exception {
        return query("select message_key from outbox where payload like '%\"aggregateId\":\"" + aggregateId + "\"%'");
    }

    private static List<String> storedEventIds(String paymentId) throws Exception {
        return query("select event_id::text from payment_events where payment_id = '" + paymentId
                + "' order by version");
    }

    private static List<String> query(String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection c = DATABASE.superuserConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static long scalar(String sql) throws Exception {
        return Long.parseLong(query(sql).getFirst());
    }
}
