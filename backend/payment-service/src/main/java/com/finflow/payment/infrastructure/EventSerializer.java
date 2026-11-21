package com.finflow.payment.infrastructure;

import com.finflow.payment.domain.PaymentEvent;
import com.finflow.payment.domain.PaymentState;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Événements et snapshots en JSON. Le type stocké est le nom simple du record. */
@Component
class EventSerializer {

    private static final Map<String, Class<? extends PaymentEvent>> TYPES = Arrays.stream(
                    PaymentEvent.class.getPermittedSubclasses())
            .map(c -> c.asSubclass(PaymentEvent.class))
            .collect(Collectors.toUnmodifiableMap(Class::getSimpleName, Function.identity()));

    private final JsonMapper json;

    EventSerializer(JsonMapper json) {
        this.json = json;
    }

    String write(Object value) {
        return json.writeValueAsString(value);
    }

    PaymentEvent readEvent(String type, String payload) {
        Class<? extends PaymentEvent> target = TYPES.get(type);
        if (target == null) {
            throw new IllegalStateException("Type d'événement inconnu : " + type);
        }
        return json.readValue(payload, target);
    }

    PaymentState readState(String payload) {
        return json.readValue(payload, PaymentState.class);
    }

    Object readTree(String payload) {
        return json.readTree(payload);
    }
}
