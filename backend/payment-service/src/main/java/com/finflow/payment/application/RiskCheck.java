package com.finflow.payment.application;

import com.finflow.payment.domain.Payment;
import java.util.Optional;

/**
 * Étape « risque » de la saga (ADR-001). Aujourd'hui les limites du compte ; demain un appel au
 * service Risk, sans changer l'appelant.
 */
public interface RiskCheck {

    Decision evaluate(Payment payment, Optional<AccountView> source, Optional<AccountView> destination,
                      long committedTodayCents);

    /** Décision explicable : règle déclenchée et détail (valeur observée, seuil). */
    record Decision(boolean accepted, String rule, String detail) {

        public static Decision accept() {
            return new Decision(true, null, null);
        }

        public static Decision reject(String rule, String detail) {
            return new Decision(false, rule, detail);
        }
    }
}
