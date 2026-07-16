package dev.oranegonzales.ledgerrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.oranegonzales.ledgerrail.reconciliation.ReconciliationService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TransferApiIntegrationTest {

    private static final String API_KEY = "test-api-key";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("ledgerrail.security.api-key", () -> API_KEY);
        registry.add("ledgerrail.security.public-demo.requests-per-minute", () -> 1000);
        registry.add("ledgerrail.security.public-demo.writes-per-day", () -> 1);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ReconciliationService reconciliationService;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE reconciliation_results, demo_usage_daily,
                               outbox_events, ledger_entries, transfers CASCADE
                """);
    }

    @Test
    void createsBalancedLedgerAndReplaysIdenticalRequest() throws Exception {
        UUID accountId = UUID.randomUUID();
        String key = "transfer-test-001";
        String body = request(accountId, "PAY_IN", "125.50", "jmd");

        MvcResult first = mockMvc.perform(post("/api/v1/transfers")
                        .header("X-Portfolio-Key", API_KEY)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.currency").value("JMD"))
                .andReturn();

        JsonNode created = objectMapper.readTree(first.getResponse().getContentAsString());
        String transferId = created.get("id").asText();

        mockMvc.perform(post("/api/v1/transfers")
                        .header("X-Portfolio-Key", API_KEY)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(transferId));

        mockMvc.perform(get("/api/v1/transfers/{id}/ledger-entries", transferId)
                        .header("X-Portfolio-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        Integer transferCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transfers", Integer.class);
        Integer ledgerCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Integer.class);
        Integer outboxCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class);
        assertThat(transferCount).isEqualTo(1);
        assertThat(ledgerCount).isEqualTo(2);
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void rejectsReuseOfIdempotencyKeyWithDifferentRequest() throws Exception {
        UUID accountId = UUID.randomUUID();
        String key = "transfer-test-002";

        mockMvc.perform(post("/api/v1/transfers")
                        .header("X-Portfolio-Key", API_KEY)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(accountId, "PAY_OUT", "20.00", "USD")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transfers")
                        .header("X-Portfolio-Key", API_KEY)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(accountId, "PAY_OUT", "25.00", "USD")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Idempotency conflict"));
    }

    @Test
    void publicDemoWorksWithoutApiKeyAndEnforcesPersistentWriteQuota() throws Exception {
        UUID accountId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "public-invalid-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(accountId, "PAY_IN", "-1.00", "JMD")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "public-demo-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(accountId, "PAY_IN", "10.00", "JMD")))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-RateLimit-Policy", "1000 requests/minute; 1 writes/day"));

        mockMvc.perform(get("/api/v1/transfers").param("accountId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "public-demo-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(UUID.randomUUID(), "PAY_OUT", "5.00", "USD")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.title").value("Public demo limit reached"));
    }

    @Test
    void rejectsInvalidKeyAndProtectsOperatorEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/transfers")
                        .header("X-Portfolio-Key", "wrong-key")
                        .param("accountId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("The supplied X-Portfolio-Key is invalid."));

        mockMvc.perform(get("/api/v1/operations/reconciliation"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/operations/reconciliation")
                        .header("X-Portfolio-Key", API_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void operatorCanReplayFailedOutboxEvent() throws Exception {
        UUID accountId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/transfers")
                        .header("X-Portfolio-Key", API_KEY)
                        .header("Idempotency-Key", "operator-replay-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(accountId, "PAY_IN", "12.50", "JMD")))
                .andExpect(status().isCreated());
        UUID eventId = jdbcTemplate.queryForObject("SELECT id FROM outbox_events", UUID.class);
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET status = 'FAILED', attempts = 8, last_error = 'broker unavailable'
                WHERE id = ?
                """, eventId);

        mockMvc.perform(post("/api/v1/operations/outbox/{eventId}/replay", eventId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/operations/outbox/{eventId}/replay", eventId)
                        .header("X-Portfolio-Key", API_KEY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.replayCount").value(1));

        Map<String, Object> replayed = jdbcTemplate.queryForMap("""
                SELECT status, attempts, replay_count, last_replayed_at, last_error
                FROM outbox_events WHERE id = ?
                """, eventId);
        assertThat(replayed.get("status")).isEqualTo("PENDING");
        assertThat(replayed.get("attempts")).isEqualTo(0);
        assertThat(replayed.get("replay_count")).isEqualTo(1);
        assertThat(replayed.get("last_replayed_at")).isNotNull();
        assertThat(replayed.get("last_error")).isNull();
    }

    @Test
    void reconciliationPersistsLedgerMismatchForOperatorInspection() throws Exception {
        UUID accountId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/transfers")
                        .header("X-Portfolio-Key", API_KEY)
                        .header("Idempotency-Key", "reconciliation-mismatch-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(accountId, "PAY_IN", "25.00", "JMD")))
                .andExpect(status().isCreated());

        Map<String, Object> outbox = jdbcTemplate.queryForMap(
                "SELECT id, payload::text AS payload FROM outbox_events");
        UUID eventId = (UUID) outbox.get("id");
        jdbcTemplate.update("""
                UPDATE ledger_entries
                SET amount = 24.00
                WHERE transfer_id = (SELECT aggregate_id FROM outbox_events WHERE id = ?)
                  AND entry_type = 'DEBIT'
                """, eventId);

        reconciliationService.reconcile(eventId, (String) outbox.get("payload"));

        Map<String, Object> result = jdbcTemplate.queryForMap("""
                SELECT outcome, detail FROM reconciliation_results WHERE event_id = ?
                """, eventId);
        assertThat(result.get("outcome")).isEqualTo("MISMATCH");
        assertThat(result.get("detail").toString()).contains("debit total differs from PostgreSQL");
    }

    @Test
    void servesPublicDashboardAndApiMetadata() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"))
                .andExpect(header().string("X-Frame-Options", "DENY"));

        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("LedgerRail Core")))
                .andExpect(header().string("X-Frame-Options", "DENY"));

        mockMvc.perform(get("/api-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("LedgerRail Core"))
                .andExpect(jsonPath("$.version").value("0.3.0"));
    }

    private String request(UUID accountId, String type, String amount, String currency) {
        return """
                {
                  "accountId": "%s",
                  "type": "%s",
                  "amount": %s,
                  "currency": "%s"
                }
                """.formatted(accountId, type, amount, currency);
    }
}
