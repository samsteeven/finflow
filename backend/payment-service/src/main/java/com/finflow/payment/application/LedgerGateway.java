package com.finflow.payment.application;

import java.util.UUID;

/** Comptabilisation par le Ledger, au nom de l'appelant (ADR-012). */
public interface LedgerGateway {

    /** Comptabilise le virement du paiement ; rejouable sans risque (la clé est le paiement). */
    Outcome transfer(UUID paymentId, UUID source, UUID destination, long amountCents, String description);

    /** Contrepasse une transaction comptabilisée ; rejouable sans risque (la clé est le remboursement). */
    Outcome reverse(UUID refundId, UUID ledgerTransactionId, String description);

    sealed interface Outcome {
        /** Le Ledger a comptabilisé (ou l'avait déjà fait : rejeu). */
        record Posted(UUID transactionId) implements Outcome {
        }

        /** Refus métier : code stable (FONDS_INSUFFISANTS, NON_CONTREPASSABLE) et détail lisible. */
        record Refused(String code, String detail) implements Outcome {
        }

        /** Panne, délai dépassé ou réponse inexploitable : l'issue est inconnue, il faudra rejouer. */
        record Unavailable(String reason) implements Outcome {
        }
    }
}
