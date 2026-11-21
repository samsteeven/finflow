package com.finflow.account.projection;

import com.finflow.events.EventEnvelope;
import com.finflow.messaging.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Point d'entrée Kafka ; toute la logique est dans {@link EventProjector}. */
@Component
@ConditionalOnProperty(name = "finflow.messaging.consumer.enabled", havingValue = "true")
class KafkaEventListener {

    private final EventProjector projector;

    KafkaEventListener(EventProjector projector) {
        this.projector = projector;
    }

    @KafkaListener(topics = {Topics.PAYMENT, Topics.LEDGER})
    void onEvent(ConsumerRecord<String, EventEnvelope> record) {
        projector.apply(record.key(), record.value());
    }
}
