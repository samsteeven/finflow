package com.finflow.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finflow.payment.domain.PaymentEvent.PaymentCreated;
import com.finflow.payment.domain.PaymentEvent.PaymentValidationStarted;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final Instant T0 = Instant.parse("2026-11-20T18:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void un_paiement_initie_est_cree_puis_part_en_validation() {
        Payment payment = initiate();

        assertThat(payment.status()).isEqualTo(PaymentStatus.VALIDATING);
        assertThat(payment.pendingEvents()).extracting(PaymentEvent::type)
                .containsExactly("PaymentCreated", "PaymentValidationStarted");
        assertThat(payment.version()).isEqualTo(2);
        assertThat(payment.expectedVersion()).isZero();
    }

    @Test
    void un_paiement_invalide_n_est_pas_cree() {
        assertThatThrownBy(() -> Payment.initiate(UUID.randomUUID(), TENANT, A, A, 100, "EUR", null, "u", T0))
                .isInstanceOf(InvalidPaymentException.class);
        assertThatThrownBy(() -> Payment.initiate(UUID.randomUUID(), TENANT, A, B, 0, "EUR", null, "u", T0))
                .isInstanceOf(InvalidPaymentException.class);
        assertThatThrownBy(() -> Payment.initiate(UUID.randomUUID(), TENANT, A, B, 100, "USD", null, "u", T0))
                .isInstanceOf(InvalidPaymentException.class);
    }

    @Test
    void le_parcours_nominal_va_jusqu_au_remboursement() {
        Payment payment = initiate();
        UUID ledgerTx = UUID.randomUUID();
        UUID refundTx = UUID.randomUUID();

        payment.validate(T0.plusSeconds(1));
        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
        payment.complete(ledgerTx, T0.plusSeconds(2));
        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
        payment.requestRefund("erreur de destinataire", T0.plusSeconds(3));
        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_PENDING);
        payment.completeRefund(refundTx, T0.plusSeconds(4));

        PaymentState state = payment.state();
        assertThat(state.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(state.ledgerTransactionId()).isEqualTo(ledgerTx);
        assertThat(state.refundLedgerTransactionId()).isEqualTo(refundTx);
        assertThat(state.version()).isEqualTo(6);
        assertThat(state.updatedAt()).isEqualTo(T0.plusSeconds(4));
    }

    @Test
    void un_rejet_est_explicable_et_definitif() {
        Payment payment = initiate();
        payment.reject("LIMITE_PAR_OPERATION", "montant 150000 > limite 100000", T0);

        assertThat(payment.state().rejection())
                .isEqualTo(new Payment.Rejection("LIMITE_PAR_OPERATION", "montant 150000 > limite 100000"));
        assertThatThrownBy(() -> payment.validate(T0)).isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> payment.complete(UUID.randomUUID(), T0))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void les_reprises_apres_echec_technique_sont_bornees() {
        Payment payment = initiate();
        payment.validate(T0);
        for (int attempt = 1; attempt <= Payment.MAX_RETRIES; attempt++) {
            payment.fail("Ledger indisponible", T0);
            payment.retry(T0);
            assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
        }
        payment.fail("Ledger indisponible", T0);

        assertThatThrownBy(() -> payment.retry(T0))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("nombre maximal de reprises");
    }

    @Test
    void on_ne_rembourse_qu_un_paiement_termine() {
        Payment payment = initiate();
        assertThatThrownBy(() -> payment.requestRefund("x", T0)).isInstanceOf(IllegalTransitionException.class);
        payment.validate(T0);
        assertThatThrownBy(() -> payment.requestRefund("x", T0)).isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> payment.completeRefund(UUID.randomUUID(), T0))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void le_ledger_peut_rejeter_un_paiement_en_cours_de_comptabilisation() {
        Payment payment = initiate();
        payment.validate(T0.plusSeconds(1));

        payment.reject("FONDS_INSUFFISANTS", "solde insuffisant", T0.plusSeconds(2));

        assertThat(payment.status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(payment.state().rejection().rule()).isEqualTo("FONDS_INSUFFISANTS");
        assertThatThrownBy(() -> payment.complete(UUID.randomUUID(), T0.plusSeconds(3)))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void un_paiement_termine_ne_peut_plus_etre_rejete() {
        Payment payment = initiate();
        payment.validate(T0.plusSeconds(1));
        payment.complete(UUID.randomUUID(), T0.plusSeconds(2));

        assertThatThrownBy(() -> payment.reject("X", "y", T0.plusSeconds(3)))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void un_remboursement_refuse_remet_le_paiement_a_termine_et_chaque_demande_a_sa_propre_cle() {
        Payment payment = initiate();
        payment.validate(T0.plusSeconds(1));
        payment.complete(UUID.randomUUID(), T0.plusSeconds(2));

        payment.requestRefund("première demande", T0.plusSeconds(3));
        UUID firstRefund = payment.refundId();
        assertThat(firstRefund).isNotNull();
        payment.rejectRefund("FONDS_INSUFFISANTS : le destinataire a dépensé", T0.plusSeconds(4));

        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.refundId()).isNull();
        assertThat(payment.state().refundRejection()).contains("FONDS_INSUFFISANTS");

        payment.requestRefund("seconde demande", T0.plusSeconds(5));
        assertThat(payment.refundId()).isNotNull().isNotEqualTo(firstRefund);
        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_PENDING);
    }

    @Test
    void on_ne_refuse_un_remboursement_que_s_il_est_en_attente() {
        Payment payment = initiate();
        payment.validate(T0.plusSeconds(1));
        payment.complete(UUID.randomUUID(), T0.plusSeconds(2));

        assertThatThrownBy(() -> payment.rejectRefund("x", T0.plusSeconds(3)))
                .isInstanceOf(IllegalTransitionException.class);
    }


    @Test
    void le_rejeu_des_evenements_reconstruit_exactement_l_etat() {
        Payment original = initiate();
        original.validate(T0.plusSeconds(1));
        original.complete(UUID.randomUUID(), T0.plusSeconds(2));

        Payment replayed = Payment.replay(original.pendingEvents());

        assertThat(replayed.state()).isEqualTo(original.state());
        assertThat(replayed.pendingEvents()).isEmpty();
    }

    @Test
    void un_snapshot_suivi_des_evenements_restants_donne_le_meme_etat_que_le_rejeu_complet() {
        Payment original = initiate();
        original.validate(T0.plusSeconds(1));
        List<PaymentEvent> all = original.pendingEvents();
        PaymentState atVersion2 = Payment.replay(all.subList(0, 2)).state();

        Payment fromSnapshot = Payment.replay(atVersion2, all.subList(2, all.size()));

        assertThat(fromSnapshot.state()).isEqualTo(Payment.replay(all).state());
    }

    @Test
    void un_evenement_d_un_autre_paiement_ou_une_seconde_creation_sont_refuses_au_rejeu() {
        Payment original = initiate();
        List<PaymentEvent> events = original.pendingEvents();
        UUID other = UUID.randomUUID();

        assertThatThrownBy(() -> Payment.replay(List.of(events.getFirst(), new PaymentValidationStarted(other, T0))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Payment.replay(List.of(events.getFirst(), new PaymentCreated(
                original.id(), TENANT, A, B, 1, "EUR", null, "u", T0))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Payment.replay(List.of())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void la_version_attendue_suit_les_evenements_deja_ecrits() {
        Payment payment = initiate();
        payment.markCommitted();
        payment.validate(T0);

        assertThat(payment.expectedVersion()).isEqualTo(2);
        assertThat(payment.pendingEvents()).hasSize(1);
    }

    private static Payment initiate() {
        return Payment.initiate(UUID.randomUUID(), TENANT, A, B, 150_000, "EUR", "Loyer", "alice", T0);
    }
}
