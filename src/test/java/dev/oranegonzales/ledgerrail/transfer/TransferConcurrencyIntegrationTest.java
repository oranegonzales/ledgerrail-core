package dev.oranegonzales.ledgerrail.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "ledgerrail.security.api-key=concurrency-test-api-key-00000000001")
@Testcontainers
class TransferConcurrencyIntegrationTest {

    private static final int CALLERS = 24;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TransferService transferService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE reconciliation_results, demo_usage_daily,
                               outbox_events, ledger_entries, transfers CASCADE
                """);
    }

    @Test
    void concurrentRetriesCommitExactlyOneFinancialTransfer() throws Exception {
        UUID accountId = UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(
                accountId, TransferType.PAY_IN, new BigDecimal("125.50"), "JMD");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CreateTransferResult>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < CALLERS; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return transferService.create("concurrent-idempotency-001", request);
                }));
            }
            start.countDown();

            List<CreateTransferResult> results = new ArrayList<>();
            for (Future<CreateTransferResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(results).extracting(CreateTransferResult::transfer)
                    .extracting(TransferResponse::id)
                    .containsOnly(results.getFirst().transfer().id());
            assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
            assertThat(results).filteredOn(CreateTransferResult::replayed).hasSize(CALLERS - 1);
        }

        assertThat(count("transfers")).isEqualTo(1);
        assertThat(count("ledger_entries")).isEqualTo(2);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
