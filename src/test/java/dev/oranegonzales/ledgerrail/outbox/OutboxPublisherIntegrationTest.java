package dev.oranegonzales.ledgerrail.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "ledgerrail.kafka.enabled=true",
        "ledgerrail.kafka.consumer-enabled=true",
        "ledgerrail.kafka.initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@Testcontainers
class OutboxPublisherIntegrationTest {

    private static final String API_KEY = "integration-test-api-key";
    private static final String TOPIC = "transfer.completed.v1";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("apache/kafka-native:3.8.0"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("ledgerrail.security.api-key", () -> API_KEY);
        registry.add("ledgerrail.kafka.topic", () -> TOPIC);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE reconciliation_results, demo_usage_daily,
                               outbox_events, ledger_entries, transfers CASCADE
                """);
    }

    @Test
    void publishesCommittedTransferEventAndMarksOutboxRow() throws Exception {
        UUID accountId = UUID.randomUUID();
        String request = """
                {
                  "accountId": "%s",
                  "type": "PAY_IN",
                  "amount": 125.50,
                  "currency": "JMD"
                }
                """.formatted(accountId);
        MvcResult response = mockMvc.perform(post("/api/v1/transfers")
                        .header("X-Portfolio-Key", API_KEY)
                        .header("Idempotency-Key", "kafka-integration-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn();
        String transferId = objectMapper.readTree(response.getResponse().getContentAsString())
                .get("id").asText();

        assertThat(publisher.publishAvailable()).isEqualTo(1);

        ConsumerRecord<String, String> record = consumeOne();
        JsonNode payload = objectMapper.readTree(record.value());
        assertThat(record.key()).isEqualTo(transferId);
        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(payload.get("transferId").asText()).isEqualTo(transferId);
        assertThat(payload.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(new String(record.headers().lastHeader("event_id").value(), StandardCharsets.UTF_8))
                .isEqualTo(payload.get("eventId").asText());
        assertThat(new String(record.headers().lastHeader("event_type").value(), StandardCharsets.UTF_8))
                .isEqualTo(TOPIC);

        Map<String, Object> delivery = jdbcTemplate.queryForMap("""
                SELECT status, attempts, published_at, last_error
                FROM outbox_events
                WHERE aggregate_id = ?::uuid
                """, transferId);
        assertThat(delivery.get("status")).isEqualTo("PUBLISHED");
        assertThat(delivery.get("attempts")).isEqualTo(1);
        assertThat(delivery.get("published_at")).isNotNull();
        assertThat(delivery.get("last_error")).isNull();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Map<String, Object> reconciliation = jdbcTemplate.queryForMap("""
                    SELECT outcome, detail
                    FROM reconciliation_results
                    WHERE event_id = ?::uuid
                    """, payload.get("eventId").asText());
            assertThat(reconciliation.get("outcome")).isEqualTo("MATCHED");
            assertThat(reconciliation.get("detail"))
                    .isEqualTo("Transfer, ledger, payload, and outbox agree");
        });

        ProducerRecord<String, String> duplicate = new ProducerRecord<>(TOPIC, transferId, record.value());
        record.headers().forEach(header -> duplicate.headers().add(header.key(), header.value()));
        kafkaTemplate.send(duplicate).get(10, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Integer reconciliationCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM reconciliation_results", Integer.class);
            assertThat(reconciliationCount).isEqualTo(1);
            assertThat(meterRegistry.get("ledgerrail.reconciliation.events")
                    .tag("outcome", "duplicate").counter().count()).isGreaterThanOrEqualTo(1.0);
        });
    }

    private ConsumerRecord<String, String> consumeOne() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "ledgerrail-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    return record;
                }
            }
        }
        throw new AssertionError("No Kafka record received within 15 seconds");
    }
}
