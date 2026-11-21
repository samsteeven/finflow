package com.finflow.messaging;

import com.finflow.events.EventEnvelope;
import com.finflow.events.PaymentChanged;
import com.finflow.events.PostedLine;
import com.finflow.events.Side;
import com.finflow.events.TransactionPosted;
import org.apache.avro.util.ClassSecurityValidator;

/**
 * Avro 1.12 refuse de charger une classe générée qu'il ne connaît pas. On déclare de confiance les
 * seules classes des contrats FinFlow, en gardant les règles par défaut pour le reste.
 */
public final class AvroTrust {

    private static boolean installed;

    private AvroTrust() {
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(ClassSecurityValidator.getGlobal(),
                ClassSecurityValidator.builder()
                        .add(EventEnvelope.class).add(PaymentChanged.class).add(TransactionPosted.class)
                        .add(PostedLine.class).add(Side.class).build()));
        installed = true;
    }
}
