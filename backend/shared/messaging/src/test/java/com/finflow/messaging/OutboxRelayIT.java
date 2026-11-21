package com.finflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.finflow.events.EventEnvelope;
import com.finflow.events.PaymentChanged;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Le relais contre un vrai Kafka et un vrai PostgreSQL : ordre, reprise après une panne du broker,
 * un seul relais actif à la fois, livraison au moins une fois (ADR-013). Le Schema Registry est
 * remplacé par celui, en mémoire, de la bibliothèque Confluent ({@code mock://}).
 */
class OutboxRelayIT {

    static PostgreSQLContainer postgres;
    static KafkaContainer kafka;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static Outbox outbox;
    static final String REGISTRY = "mock://relay-it";

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer("postgres:18-alpine");
        postgres.start();
        kafka = new KafkaContainer("apache/kafka:4.3.2");
        kafka.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        outbox = new Outbox(jdbc);
        jdbc.execute("""
                create table outbox (
                    id bigserial primary key, event_id uuid not null unique, topic varchar(100) not null,
                    message_key varchar(100) not null, payload text not null,
                    created_at timestamptz not null default now(), published_at timestamptz,
                    attempts integer not null default 0, last_error text)""");
    }

    @AfterAll
    static void stop() {
        kafka.stop();
        postgres.stop();
    }

    @BeforeEach
    void emptyOutbox() {
        jdbc.update("delete from outbox");
    }

    @Test
    void les_evenements_partent_dans_l_ordre_et_sont_marques_publies() {
        String topic = topic();
        UUID aggregate = UUID.randomUUID();
        List<String> ids = append(topic, aggregate, 5);

        OutboxRelay relay = relay(kafka.getBootstrapServers());
        assertThat(relay.publishPending()).isEqualTo(5);

        assertThat(relay.pending()).isZero();
        List<ConsumerRecord<String, EventEnvelope>> records = consume(topic, 5);
        assertThat(records).extracting(r -> r.value().getEventId()).containsExactlyElementsOf(ids);
        assertThat(records).extracting(ConsumerRecord::key).containsOnly(Envelopes.partitionKey(records.getFirst().value()));
        // Même clé, donc même partition : l'ordre par agrégat est garanti.
        assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.getFirst().partition());
    }

    @Test
    void kafka_injoignable_laisse_les_evenements_en_attente_puis_ils_partent_au_retour() {
        String topic = topic();
        List<String> ids = append(topic, UUID.randomUUID(), 3);

        OutboxRelay broken = relay("localhost:1");
        assertThat(broken.publishPending()).isZero();

        assertThat(broken.pending()).isEqualTo(3);
        // Le lot s'arrête au premier échec : un événement ne dépasse jamais un plus ancien.
        assertThat(jdbc.queryForList("select attempts from outbox order by id", Integer.class))
                .containsExactly(1, 0, 0);
        assertThat(jdbc.queryForObject("select last_error from outbox order by id limit 1", String.class)).isNotBlank();

        OutboxRelay recovered = relay(kafka.getBootstrapServers());
        assertThat(recovered.publishPending()).isEqualTo(3);
        assertThat(consume(topic, 3)).extracting(r -> r.value().getEventId()).containsExactlyElementsOf(ids);
    }

    @Test
    void deux_relais_simultanes_ne_publient_jamais_deux_fois_le_meme_evenement() throws Exception {
        String topic = topic();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.addAll(append(topic, UUID.randomUUID(), 5));
        }
        OutboxRelay first = relay(kafka.getBootstrapServers());
        OutboxRelay second = relay(kafka.getBootstrapServers());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<Void>> runs = new ArrayList<>();
            for (OutboxRelay relay : List.of(first, second)) {
                runs.add(CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < 40 && first.pending() > 0; i++) {
                        relay.publishPending();
                    }
                }, pool));
            }
            CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).get();
        } finally {
            pool.shutdownNow();
        }

        List<ConsumerRecord<String, EventEnvelope>> records = consume(topic, 30);
        assertThat(records).extracting(r -> r.value().getEventId()).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(records).extracting(r -> r.value().getEventId()).doesNotHaveDuplicates();
    }

    @Test
    void un_arret_avant_la_marque_republie_l_evenement_au_moins_une_fois() {
        String topic = topic();
        List<String> ids = append(topic, UUID.randomUUID(), 1);
        OutboxRelay relay = relay(kafka.getBootstrapServers());
        relay.publishPending();

        // Le processus s'est arrêté après l'envoi et avant la marque : l'événement repart.
        jdbc.update("update outbox set published_at = null");
        relay.publishPending();

        assertThat(consume(topic, 2)).extracting(r -> r.value().getEventId()).containsExactly(ids.getFirst(),
                ids.getFirst());
    }

    @Test
    void la_purge_ne_supprime_que_les_evenements_publies_depuis_longtemps() {
        String topic = topic();
        append(topic, UUID.randomUUID(), 3);
        OutboxRelay relay = relay(kafka.getBootstrapServers());
        relay.publishPending();
        append(topic, UUID.randomUUID(), 1); // en attente
        jdbc.update("update outbox set published_at = now() - interval '30 days' where published_at is not null"
                + " and id = (select min(id) from outbox)");

        assertThat(relay.purge()).isEqualTo(1);

        assertThat(jdbc.queryForObject("select count(*) from outbox", Long.class)).isEqualTo(3);
        assertThat(relay.pending()).isEqualTo(1);
    }

    // --- aides -----------------------------------------------------------------------------------

    private static String topic() {
        return "relay-it-" + UUID.randomUUID();
    }

    private static List<String> append(String topic, UUID aggregate, int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            EventEnvelope envelope = Envelopes.of(UUID.randomUUID(), "test", "PaymentChanged", aggregate, i,
                    UUID.fromString("a1a1a1a1-0000-4000-8000-000000000001"), "corr", null, Instant.now(),
                    PaymentChanged.newBuilder().setStatus("S" + i).setSourceAccountId("a").setDestinationAccountId("b")
                            .setAmountCents(100).setCurrency("EUR").build());
            tx.executeWithoutResult(status -> outbox.append(topic, envelope));
            ids.add(envelope.getEventId());
        }
        return ids;
    }

    private static OutboxRelay relay(String bootstrap) {
        MessagingProperties properties = new MessagingProperties(bootstrap, REGISTRY,
                new MessagingProperties.Relay(true, Duration.ofMillis(100), Duration.ofSeconds(4), 10, null), null);
        var factory = new MessagingAutoConfiguration().outboxProducerFactory(properties);
        KafkaAdmin admin = new KafkaAdmin(Map.of("bootstrap.servers", bootstrap, "request.timeout.ms", 4000));
        return new OutboxRelay(jdbc, tx, new KafkaTemplate<>(factory), properties.relay(),
                () -> admin.createOrModifyTopics(new org.apache.kafka.clients.admin.NewTopic[0]));
    }

    /** Lit exactement {@code expected} messages du topic, en le créant au besoin. */
    private static List<ConsumerRecord<String, EventEnvelope>> consume(String topic, int expected) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        config.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, REGISTRY);
        config.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        List<ConsumerRecord<String, EventEnvelope>> records = new ArrayList<>();
        try (KafkaConsumer<String, EventEnvelope> consumer = new KafkaConsumer<>(config)) {
            var partitions = consumer.partitionsFor(topic, Duration.ofSeconds(10)).stream()
                    .map(info -> new TopicPartition(topic, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        assertThat(records).hasSize(expected);
        return records;
    }
}
