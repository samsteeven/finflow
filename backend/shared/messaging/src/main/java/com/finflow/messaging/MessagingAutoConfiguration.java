package com.finflow.messaging;

import com.finflow.events.EventEnvelope;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

@AutoConfiguration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

    static {
        AvroTrust.install();
    }

    @Bean
    @ConditionalOnMissingBean
    Outbox outbox(JdbcTemplate jdbc) {
        return new Outbox(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    Inbox inbox(JdbcTemplate jdbc) {
        return new Inbox(jdbc);
    }

    private static KafkaAdmin admin(MessagingProperties properties) {
        KafkaAdmin admin = new KafkaAdmin(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 4000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 6000));
        // Le démarrage d'un service ne dépend pas de Kafka : les topics sont créés au premier besoin.
        admin.setAutoCreate(false);
        return admin;
    }

    @Bean
    @ConditionalOnProperty(name = "finflow.messaging.relay.enabled", havingValue = "true")
    ProducerFactory<String, EventEnvelope> outboxProducerFactory(MessagingProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        config.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, properties.schemaRegistryUrl());
        // Enregistrement automatique des schémas : suffisant en développement (ADR-013).
        config.put(AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS, true);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // Délais courts : un broker arrêté doit se voir vite dans l'outbox, pas bloquer le relais.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 8000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 4000);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    @ConditionalOnProperty(name = "finflow.messaging.relay.enabled", havingValue = "true")
    OutboxRelay outboxRelay(JdbcTemplate jdbc, TransactionTemplate tx,
                            ProducerFactory<String, EventEnvelope> outboxProducerFactory,
                            MessagingProperties properties) {
        KafkaAdmin admin = admin(properties);
        return new OutboxRelay(jdbc, tx, new KafkaTemplate<>(outboxProducerFactory), properties.relay(),
                () -> admin.createOrModifyTopics(Topics.all().toArray(new org.apache.kafka.clients.admin.NewTopic[0])));
    }

    @EnableKafka
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "finflow.messaging.consumer.enabled", havingValue = "true")
    static class ConsumerConfiguration {

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, EventEnvelope> kafkaListenerContainerFactory(
                MessagingProperties properties, @Value("${spring.application.name}") String groupId) {
            Map<String, Object> config = new HashMap<>();
            config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
            config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
            config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            // Un message illisible ne bloque pas la partition : il part en file morte (ADR-014).
            config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
            config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, KafkaAvroDeserializer.class);
            config.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, properties.schemaRegistryUrl());
            config.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);

            ConcurrentKafkaListenerContainerFactory<String, EventEnvelope> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
            factory.setCommonErrorHandler(errorHandler(properties));
            return factory;
        }

        private static DefaultErrorHandler errorHandler(MessagingProperties properties) {
            Map<String, Object> config = new HashMap<>();
            config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
            config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            config.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, properties.schemaRegistryUrl());
            config.put(AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS, true);
            KafkaAvroSerializer avro = new KafkaAvroSerializer();
            avro.configure(config, false);
            // Un message illisible repart tel quel (octets), un message lisible en Avro.
            Map<Class<?>, org.apache.kafka.common.serialization.Serializer<?>> serializers = new java.util.LinkedHashMap<>();
            serializers.put(byte[].class, new ByteArraySerializer());
            serializers.put(EventEnvelope.class, avro);
            DefaultKafkaProducerFactory<String, Object> producers = new DefaultKafkaProducerFactory<>(config,
                    new StringSerializer(), new DelegatingByTypeSerializer(serializers));
            DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                    new KafkaTemplate<>(producers),
                    (record, error) -> new TopicPartition(Topics.deadLetter(record.topic()), -1));
            DefaultErrorHandler handler = new DefaultErrorHandler(recoverer,
                    new FixedBackOff(properties.consumer().retryInterval().toMillis(),
                            properties.consumer().maxRetries()));
            // Un événement mal formé ne se corrige pas en réessayant.
            handler.addNotRetryableExceptions(IllegalArgumentException.class);
            return handler;
        }
    }
}
