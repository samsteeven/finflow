package com.finflow.account.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.finflow.account.PostgresIntegrationTest;
import com.finflow.events.EventEnvelope;
import com.finflow.events.PaymentChanged;
import com.finflow.messaging.Envelopes;
import com.finflow.messaging.Topics;
import com.finflow.testing.TestIssuer;
import com.jayway.jsonpath.JsonPath;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Le consommateur contre un vrai Kafka : projection d'un événement publié, message mal formé ou
 * illisible envoyé en file morte sans bloquer la partition (ADR-014).
 */
@SpringBootTest(properties = {"finflow.messaging.consumer.enabled=true", "finflow.messaging.consumer.max-retries=1",
        "finflow.messaging.consumer.retry-interval=100ms", "spring.kafka.listener.missing-topics-fatal=false"})
@AutoConfigureMockMvc
class KafkaConsumerIT extends PostgresIntegrationTest {

    static final String REGISTRY = "mock://consumer-it";
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

    static {
        KAFKA.start();
        new KafkaAdmin(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))
                .createOrModifyTopics(Topics.all().toArray(new org.apache.kafka.clients.admin.NewTopic[0]));
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("finflow.messaging.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("finflow.messaging.schema-registry-url", () -> REGISTRY);
    }

    @Autowired MockMvc mvc;

    @Test
    void un_evenement_publie_sur_kafka_est_projete_puis_les_mal_formes_vont_en_file_morte() throws Exception {
        UUID tenant = UUID.randomUUID();
        String admin = "Bearer " + TestIssuer.shared().token().tenant(tenant).roles("tenant-admin").build();
        UUID account = open(admin);
        UUID paymentA = UUID.randomUUID();
        UUID paymentB = UUID.randomUUID();

        // 1. Un événement valide est projeté.
        send(Envelopes.partitionKey(event(tenant, paymentA, 1, "CREATED", account)), event(tenant, paymentA, 1, "CREATED", account));
        awaitStatus(admin, account, paymentA, "CREATED");

        // 2. Un événement dont la clé ne correspond pas à l'enveloppe part en file morte, sans nouvel essai.
        EventEnvelope poison = event(tenant, paymentB, 1, "CREATED", account);
        send("cle-falsifiee", poison);
        // 3. Un message illisible part lui aussi en file morte, tel quel.
        sendRaw("cle-brute", "pas de l'avro".getBytes());
        // 4. La partition n'est pas bloquée : l'événement suivant est projeté.
        EventEnvelope next = event(tenant, paymentA, 2, "COMPLETED", account);
        send(Envelopes.partitionKey(next), next);
        awaitStatus(admin, account, paymentA, "COMPLETED");

        List<ConsumerRecord<String, byte[]>> dead = deadLetters(Topics.deadLetter(Topics.PAYMENT), 2);
        assertThat(dead).extracting(ConsumerRecord::key).containsExactlyInAnyOrder("cle-falsifiee", "cle-brute");
        assertThat(dead).allSatisfy(record -> assertThat(record.headers().lastHeader("kafka_dlt-exception-message"))
                .isNotNull());
        // Rien du paiement mal formé n'a été projeté.
        mvc.perform(get("/api/v1/accounts/" + account + "/activity").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.payments.length()").value(1));
    }

    // --- aides -----------------------------------------------------------------------------------

    private UUID open(String admin) throws Exception {
        String json = mvc.perform(post("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Courant","type":"CURRENT","perTransactionLimitCents":100000,"dailyLimitCents":500000}"""))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private static EventEnvelope event(UUID tenant, UUID payment, long version, String status, UUID account) {
        return Envelopes.of(UUID.randomUUID(), "payment-service", "PaymentChanged", payment, version, tenant, "corr",
                null, Instant.now(), PaymentChanged.newBuilder().setStatus(status).setSourceAccountId(account.toString())
                        .setDestinationAccountId(UUID.randomUUID().toString()).setAmountCents(100).setCurrency("EUR")
                        .build());
    }

    private void awaitStatus(String admin, UUID account, UUID payment, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String last = "";
        while (System.nanoTime() < deadline) {
            String json = mvc.perform(get("/api/v1/accounts/" + account + "/activity")
                    .header(HttpHeaders.AUTHORIZATION, admin)).andReturn().getResponse().getContentAsString();
            List<String> statuses = JsonPath.read(json, "$.payments[?(@.paymentId=='" + payment + "')].status");
            last = statuses.isEmpty() ? "" : statuses.getFirst();
            if (expected.equals(last)) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Statut attendu " + expected + ", dernier vu : '" + last + "'");
    }

    private static void send(String key, EventEnvelope event) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        config.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, REGISTRY);
        try (KafkaProducer<String, EventEnvelope> producer = new KafkaProducer<>(config)) {
            producer.send(new ProducerRecord<>(Topics.PAYMENT, key, event));
            producer.flush();
        }
    }

    private static void sendRaw(String key, byte[] value) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(config)) {
            producer.send(new ProducerRecord<>(Topics.PAYMENT, key, value));
            producer.flush();
        }
    }

    private static List<ConsumerRecord<String, byte[]>> deadLetters(String topic, int expected) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-it-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic, Duration.ofSeconds(10)).stream()
                    .map(info -> new TopicPartition(topic, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        assertThat(records).hasSize(expected);
        return records;
    }
}
