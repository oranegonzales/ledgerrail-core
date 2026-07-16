package dev.oranegonzales.ledgerrail.reconciliation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ReconciliationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean claim(UUID eventId, UUID transferId, Instant processedAt) {
        String sql = """
                INSERT INTO reconciliation_results (
                    event_id, transfer_id, outcome, detail, processed_at
                ) VALUES (
                    :eventId, :transferId, 'PROCESSING', 'Reconciliation started', :processedAt
                )
                ON CONFLICT (event_id) DO NOTHING
                """;
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("transferId", transferId)
                .addValue("processedAt", processedAt.atOffset(ZoneOffset.UTC))) == 1;
    }

    Optional<ReconciliationFacts> facts(UUID transferId, UUID eventId) {
        String sql = """
                SELECT t.account_id,
                       t.transfer_type,
                       t.amount,
                       t.currency,
                       t.status,
                       COUNT(le.id) AS entry_count,
                       COUNT(le.id) FILTER (WHERE le.entry_type = 'DEBIT') AS debit_count,
                       COUNT(le.id) FILTER (WHERE le.entry_type = 'CREDIT') AS credit_count,
                       COALESCE(SUM(le.amount) FILTER (WHERE le.entry_type = 'DEBIT'), 0) AS debit_total,
                       COALESCE(SUM(le.amount) FILTER (WHERE le.entry_type = 'CREDIT'), 0) AS credit_total,
                       COALESCE(BOOL_AND(le.currency = t.currency), FALSE) AS ledger_currencies_match,
                       EXISTS (
                           SELECT 1
                           FROM outbox_events outbox
                           WHERE outbox.id = :eventId
                             AND outbox.aggregate_id = t.id
                             AND outbox.event_type = 'transfer.completed.v1'
                       ) AS outbox_present
                FROM transfers t
                LEFT JOIN ledger_entries le ON le.transfer_id = t.id
                WHERE t.id = :transferId
                GROUP BY t.id
                """;
        Map<String, Object> parameters = Map.of("transferId", transferId, "eventId", eventId);
        return jdbc.query(sql, parameters, (resultSet, rowNumber) -> mapFacts(resultSet))
                .stream()
                .findFirst();
    }

    void complete(UUID eventId, ReconciliationOutcome outcome, String detail, Instant processedAt) {
        String sql = """
                UPDATE reconciliation_results
                SET outcome = :outcome,
                    detail = :detail,
                    processed_at = :processedAt
                WHERE event_id = :eventId
                  AND outcome = 'PROCESSING'
                """;
        int updated = jdbc.update(sql, new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("outcome", outcome.name())
                .addValue("detail", detail)
                .addValue("processedAt", processedAt.atOffset(ZoneOffset.UTC)));
        if (updated != 1) {
            throw new IllegalStateException("Reconciliation claim was lost for event " + eventId);
        }
    }

    public List<ReconciliationResult> findRecent(int limit) {
        String sql = """
                SELECT event_id, transfer_id, outcome, detail, processed_at
                FROM reconciliation_results
                ORDER BY processed_at DESC
                LIMIT :limit
                """;
        return jdbc.query(sql, Map.of("limit", limit), this::mapResult);
    }

    private ReconciliationFacts mapFacts(ResultSet resultSet) throws SQLException {
        return new ReconciliationFacts(
                resultSet.getObject("account_id", UUID.class),
                resultSet.getString("transfer_type"),
                resultSet.getBigDecimal("amount"),
                resultSet.getString("currency"),
                resultSet.getString("status"),
                resultSet.getInt("entry_count"),
                resultSet.getInt("debit_count"),
                resultSet.getInt("credit_count"),
                resultSet.getBigDecimal("debit_total"),
                resultSet.getBigDecimal("credit_total"),
                resultSet.getBoolean("ledger_currencies_match"),
                resultSet.getBoolean("outbox_present"));
    }

    private ReconciliationResult mapResult(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ReconciliationResult(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getObject("transfer_id", UUID.class),
                ReconciliationOutcome.valueOf(resultSet.getString("outcome")),
                resultSet.getString("detail"),
                resultSet.getTimestamp("processed_at").toInstant());
    }
}
