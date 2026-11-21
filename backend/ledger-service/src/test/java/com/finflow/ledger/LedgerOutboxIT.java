package com.finflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finflow.events.EventEnvelope;
import com.finflow.events.PostedLine;
import com.finflow.events.Side;
import com.finflow.events.TransactionPosted;
import com.finflow.messaging.AvroJson;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.ResultActions;

/** L'événement est écrit avec la comptabilisation, dans la même transaction, ou pas du tout (ADR-013). */
@SpringBootTest
@AutoConfigureMockMvc
class LedgerOutboxIT extends LedgerIntegrationTest {

    @Autowired MockMvc mvc;

    private UUID tenant;
    private String admin;
    private UUID a;
    private UUID b;

    @BeforeEach
    void deux_comptes() {
        ACCOUNTS.down(false);
        tenant = UUID.randomUUID();
        admin = bearer(tenant, "tenant-admin");
        a = ACCOUNTS.add(tenant, "ACTIVE", 200_000, 1_000_000);
        b = ACCOUNTS.add(tenant, "ACTIVE", 200_000, 1_000_000);
    }

    @Test
    void un_virement_publie_une_transaction_equilibree_avec_l_enveloppe_complete() throws Exception {
        deposit(a, 10_000);
        UUID payment = UUID.randomUUID();
        String transactionId = idOf(transfer(payment, a, b, 4_000).andExpect(status().isCreated()));

        List<EventEnvelope> events = eventsOf(tenant);
        assertThat(events).hasSize(2);
        EventEnvelope event = events.getLast();
        assertThat(event.getEventType()).isEqualTo("TransactionPosted");
        assertThat(event.getAggregateId()).isEqualTo(transactionId);
        assertThat(event.getAggregateVersion()).isEqualTo(1);
        assertThat(event.getTenantId()).isEqualTo(tenant.toString());
        assertThat(event.getProducer()).isEqualTo("ledger-service");
        assertThat(event.getEventVersion()).isEqualTo(1);
        TransactionPosted posted = (TransactionPosted) event.getPayload();
        assertThat(posted.getKind()).isEqualTo("TRANSFER");
        assertThat(posted.getLines()).extracting(PostedLine::getAccountId, PostedLine::getSide,
                        PostedLine::getAmountCents)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(a.toString(), Side.DEBIT, 4_000L),
                        org.assertj.core.groups.Tuple.tuple(b.toString(), Side.CREDIT, 4_000L));
        assertThat(outboxKeyOf(event.getEventId())).isEqualTo(tenant + ":" + transactionId);
    }

    @Test
    void rejouer_une_comptabilisation_ne_publie_rien_de_plus() throws Exception {
        deposit(a, 10_000);
        UUID payment = UUID.randomUUID();
        transfer(payment, a, b, 4_000).andExpect(status().isCreated());
        long before = eventsOf(tenant).size();

        transfer(payment, a, b, 4_000).andExpect(status().isOk());

        assertThat(eventsOf(tenant)).hasSize((int) before);
    }

    @Test
    void un_refus_ne_publie_rien() throws Exception {
        deposit(a, 1_000);
        long before = eventsOf(tenant).size();

        transfer(UUID.randomUUID(), a, b, 5_000).andExpect(status().isUnprocessableEntity());

        assertThat(eventsOf(tenant)).hasSize((int) before);
    }

    @Test
    void une_contrepassation_publie_l_inverse_et_reference_l_original() throws Exception {
        deposit(a, 10_000);
        String original = idOf(transfer(UUID.randomUUID(), a, b, 4_000).andExpect(status().isCreated()));
        String reversal = idOf(mvc.perform(post("/internal/v1/ledger/reversals")
                        .header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refundId\":\"%s\",\"transactionId\":\"%s\"}".formatted(UUID.randomUUID(), original)))
                .andExpect(status().isCreated()));

        EventEnvelope event = eventsOf(tenant).getLast();
        assertThat(event.getAggregateId()).isEqualTo(reversal);
        TransactionPosted posted = (TransactionPosted) event.getPayload();
        assertThat(posted.getKind()).isEqualTo("REVERSAL");
        assertThat(posted.getReversalOf()).isEqualTo(original);
        assertThat(posted.getLines()).extracting(PostedLine::getAccountId, PostedLine::getSide)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(a.toString(), Side.CREDIT),
                        org.assertj.core.groups.Tuple.tuple(b.toString(), Side.DEBIT));
    }

    @Test
    void il_y_a_exactement_un_evenement_par_transaction_du_journal() throws Exception {
        deposit(a, 20_000);
        transfer(UUID.randomUUID(), a, b, 3_000).andExpect(status().isCreated());
        transfer(UUID.randomUUID(), a, b, 99_000).andExpect(status().isUnprocessableEntity());
        transfer(UUID.randomUUID(), b, a, 1_000).andExpect(status().isCreated());

        assertThat((long) eventsOf(tenant).size()).isEqualTo(
                scalar("select count(*) from journal_transactions where tenant_id = '" + tenant + "'"));
    }

    // --- aides -----------------------------------------------------------------------------------

    private void deposit(UUID account, long cents) throws Exception {
        mvc.perform(post("/api/v1/ledger/deposits").header(HttpHeaders.AUTHORIZATION, admin)
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"amountCents\":%d}".formatted(account, cents)))
                .andExpect(status().isCreated());
    }

    private ResultActions transfer(UUID payment, UUID source, UUID destination, long cents) throws Exception {
        return mvc.perform(post("/internal/v1/ledger/transfers").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content(
                        "{\"paymentId\":\"%s\",\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":%d}"
                                .formatted(payment, source, destination, cents)));
    }

    private static String idOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** L'outbox n'a pas de colonne tenant : on retrouve les événements d'une organisation par leur enveloppe. */
    private static List<EventEnvelope> eventsOf(UUID tenant) throws Exception {
        List<EventEnvelope> events = new ArrayList<>();
        try (Connection c = DATABASE.superuserConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select payload from outbox order by id")) {
            while (rs.next()) {
                EventEnvelope envelope = AvroJson.read(rs.getString(1));
                if (envelope.getTenantId().equals(tenant.toString())) {
                    events.add(envelope);
                }
            }
        }
        return events;
    }

    private static String outboxKeyOf(String eventId) throws Exception {
        try (Connection c = DATABASE.superuserConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select message_key from outbox where event_id = '" + eventId + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long scalar(String sql) throws Exception {
        try (Connection c = DATABASE.superuserConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
