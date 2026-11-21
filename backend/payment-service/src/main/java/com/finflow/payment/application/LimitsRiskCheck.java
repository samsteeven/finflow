package com.finflow.payment.application;

import com.finflow.payment.domain.Payment;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Règles évaluées dans l'ordre ; la première qui refuse donne le motif. */
@Component
public class LimitsRiskCheck implements RiskCheck {

    @Override
    public Decision evaluate(Payment payment, Optional<AccountView> source, Optional<AccountView> destination,
                             long committedTodayCents) {
        if (source.isEmpty()) {
            return Decision.reject("COMPTE_SOURCE_INTROUVABLE", "compte " + payment.sourceAccountId());
        }
        if (destination.isEmpty()) {
            return Decision.reject("COMPTE_DESTINATAIRE_INTROUVABLE", "compte " + payment.destinationAccountId());
        }
        AccountView from = source.get();
        AccountView to = destination.get();
        if (!from.active()) {
            return Decision.reject("COMPTE_SOURCE_INACTIF", "statut " + from.status());
        }
        if (!to.active()) {
            return Decision.reject("COMPTE_DESTINATAIRE_INACTIF", "statut " + to.status());
        }
        if (!payment.currency().equals(from.currency()) || !payment.currency().equals(to.currency())) {
            return Decision.reject("DEVISE_INCOMPATIBLE", "paiement " + payment.currency() + ", comptes "
                    + from.currency() + " et " + to.currency());
        }
        long perTransaction = from.limits().perTransactionCents();
        if (payment.amountCents() > perTransaction) {
            return Decision.reject("LIMITE_PAR_OPERATION",
                    "montant " + payment.amountCents() + " > limite par opération " + perTransaction);
        }
        long daily = from.limits().dailyCents();
        long total = Math.addExact(committedTodayCents, payment.amountCents());
        if (total > daily) {
            return Decision.reject("LIMITE_JOURNALIERE", "déjà engagé " + committedTodayCents + " + montant "
                    + payment.amountCents() + " > limite journalière " + daily);
        }
        return Decision.accept();
    }
}
