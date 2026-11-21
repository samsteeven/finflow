package com.finflow.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Réglages communs : {@code finflow.messaging.*}. */
@ConfigurationProperties("finflow.messaging")
public record MessagingProperties(String bootstrapServers, String schemaRegistryUrl, Relay relay,
                                  Consumer consumer) {

    public MessagingProperties {
        bootstrapServers = bootstrapServers == null ? "localhost:9094" : bootstrapServers;
        schemaRegistryUrl = schemaRegistryUrl == null ? "http://localhost:8085" : schemaRegistryUrl;
        relay = relay == null ? new Relay(false, null, null, 0, null) : relay;
        consumer = consumer == null ? new Consumer(false, 0, null) : consumer;
    }

    /** Reprises avant de mettre un message en file morte (ADR-014). */
    public record Consumer(boolean enabled, int maxRetries, java.time.Duration retryInterval) {

        public Consumer {
            maxRetries = maxRetries <= 0 ? 3 : maxRetries;
            retryInterval = retryInterval == null ? Duration.ofMillis(500) : retryInterval;
        }
    }

    /** Le relais n'est lancé que si {@code enabled} est vrai : les tests de l'API n'ont pas besoin de Kafka. */
    public record Relay(boolean enabled, Duration interval, Duration sendTimeout, int batchSize, Duration retention) {

        public Relay {
            interval = interval == null ? Duration.ofMillis(500) : interval;
            sendTimeout = sendTimeout == null ? Duration.ofSeconds(5) : sendTimeout;
            batchSize = batchSize <= 0 ? 100 : batchSize;
            retention = retention == null ? Duration.ofDays(7) : retention;
        }
    }
}
