package dev.oranegonzales.ledgerrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, ledger_entries, transfers CASCADE");
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
    void requiresPortfolioApiKey() throws Exception {
        mockMvc.perform(get("/api/v1/transfers").param("accountId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
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
