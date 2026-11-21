package com.finflow.messaging;

import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;

/** Topics du noyau et leurs files mortes. Le broker n'en crée aucun tout seul (ADR-006). */
public final class Topics {

    public static final String PAYMENT = "payment.events";
    public static final String LEDGER = "ledger.events";
    public static final int PARTITIONS = 3;

    private Topics() {
    }

    public static String deadLetter(String topic) {
        return topic + ".dlq";
    }

    public static List<NewTopic> all() {
        return List.of(PAYMENT, LEDGER, deadLetter(PAYMENT), deadLetter(LEDGER)).stream()
                .map(name -> new NewTopic(name, PARTITIONS, (short) 1))
                .toList();
    }
}
