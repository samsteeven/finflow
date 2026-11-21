package com.finflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finflow.ledger.domain.InvalidPostingException;
import com.finflow.ledger.domain.Kind;
import com.finflow.ledger.domain.Line;
import com.finflow.ledger.domain.Posting;
import com.finflow.ledger.domain.Side;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void un_virement_debite_la_source_et_credite_la_destination() {
        Posting posting = Posting.transfer("payment:1", "h", a, b, 2_500, "loyer");

        assertThat(posting.kind()).isEqualTo(Kind.TRANSFER);
        assertThat(posting.lines()).containsExactly(new Line(a, Side.DEBIT, 2_500), new Line(b, Side.CREDIT, 2_500));
        assertThat(posting.deltas()).containsEntry(a, -2_500L).containsEntry(b, 2_500L);
    }

    @Test
    void un_depot_debite_la_compensation_et_credite_le_compte() {
        UUID clearing = UUID.randomUUID();
        Posting posting = Posting.deposit("deposit:1", "h", clearing, a, 10_000, null);

        assertThat(posting.deltas()).containsEntry(clearing, -10_000L).containsEntry(a, 10_000L);
    }

    @Test
    void la_somme_des_variations_est_toujours_nulle() {
        Posting posting = Posting.transfer("k", "h", a, b, 777, null);

        assertThat(posting.deltas().values().stream().mapToLong(Long::longValue).sum()).isZero();
    }

    @Test
    void une_contrepassation_est_l_inverse_ligne_a_ligne() {
        Posting original = Posting.transfer("payment:1", "h", a, b, 900, null);
        Posting reversal = Posting.reversal("refund:1", "h2", original.id(), original.lines(), null);

        assertThat(reversal.kind()).isEqualTo(Kind.REVERSAL);
        assertThat(reversal.reversalOf()).isEqualTo(original.id());
        assertThat(reversal.lines()).containsExactly(new Line(a, Side.CREDIT, 900), new Line(b, Side.DEBIT, 900));
        assertThat(reversal.deltas()).containsEntry(a, 900L).containsEntry(b, -900L);
    }

    @Test
    void une_transaction_deséquilibree_ne_se_construit_pas() {
        assertThatThrownBy(() -> new Posting(UUID.randomUUID(), Kind.TRANSFER, "k", "h", null, null,
                List.of(new Line(a, Side.DEBIT, 100), new Line(b, Side.CREDIT, 99))))
                .isInstanceOf(InvalidPostingException.class).hasMessageContaining("déséquilibrée");
    }

    @Test
    void une_transaction_d_une_seule_ligne_ne_se_construit_pas() {
        assertThatThrownBy(() -> new Posting(UUID.randomUUID(), Kind.TRANSFER, "k", "h", null, null,
                List.of(new Line(a, Side.DEBIT, 100)))).isInstanceOf(InvalidPostingException.class);
    }

    @Test
    void un_montant_nul_ou_negatif_est_refuse() {
        assertThatThrownBy(() -> new Line(a, Side.DEBIT, 0)).isInstanceOf(InvalidPostingException.class);
        assertThatThrownBy(() -> new Line(a, Side.CREDIT, -5)).isInstanceOf(InvalidPostingException.class);
    }

    @Test
    void un_virement_vers_soi_meme_est_refuse() {
        assertThatThrownBy(() -> Posting.transfer("k", "h", a, a, 100, null))
                .isInstanceOf(InvalidPostingException.class);
    }
}
