package com.finflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.finflow.events.EventEnvelope;
import com.finflow.events.PaymentChanged;
import com.finflow.events.PostedLine;
import com.finflow.events.Side;
import com.finflow.events.TransactionPosted;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AvroJsonTest {

    private static final UUID TENANT = UUID.fromString("a1a1a1a1-0000-4000-8000-000000000001");

    @Test
    void un_paiement_survit_a_l_aller_retour_avec_ses_valeurs_nulles() {
        EventEnvelope original = Envelopes.of(UUID.randomUUID(), "payment-service", "PaymentCompleted",
                UUID.randomUUID(), 4, TENANT, "corr-1", null, Instant.parse("2026-11-21T10:15:30Z"),
                PaymentChanged.newBuilder().setStatus("COMPLETED").setSourceAccountId("a")
                        .setDestinationAccountId("b").setAmountCents(15_000).setCurrency("EUR")
                        .setLedgerTransactionId("t-1").build());

        EventEnvelope read = AvroJson.read(AvroJson.write(original));

        assertThat(read).isEqualTo(original);
        assertThat(read.getCausationId()).isNull();
        assertThat(((PaymentChanged) read.getPayload()).getReason()).isNull();
    }

    @Test
    void une_transaction_survit_a_l_aller_retour_avec_ses_lignes() {
        TransactionPosted posted = TransactionPosted.newBuilder().setTransactionId("t-1").setKind("TRANSFER")
                .setLines(List.of(
                        PostedLine.newBuilder().setAccountId("a").setSide(Side.DEBIT).setAmountCents(500).build(),
                        PostedLine.newBuilder().setAccountId("b").setSide(Side.CREDIT).setAmountCents(500).build()))
                .build();
        EventEnvelope original = Envelopes.of(UUID.randomUUID(), "ledger-service", "TransactionPosted",
                UUID.randomUUID(), 1, TENANT, null, null, Instant.now(), posted);

        EventEnvelope read = AvroJson.read(AvroJson.write(original));

        assertThat(read).isEqualTo(original);
        assertThat(((TransactionPosted) read.getPayload()).getLines()).hasSize(2);
    }

    @Test
    void la_cle_de_partition_porte_l_organisation_et_l_agregat() {
        UUID aggregate = UUID.randomUUID();
        EventEnvelope event = Envelopes.of(UUID.randomUUID(), "x", "PaymentChanged", aggregate, 1, TENANT, null,
                null, Instant.now(), PaymentChanged.newBuilder().setStatus("S").setSourceAccountId("a")
                        .setDestinationAccountId("b").setAmountCents(1).setCurrency("EUR").build());

        assertThat(Envelopes.partitionKey(event)).isEqualTo(TENANT + ":" + aggregate);
    }
}
