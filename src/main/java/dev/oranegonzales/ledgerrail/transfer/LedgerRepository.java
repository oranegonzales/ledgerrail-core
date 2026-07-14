package dev.oranegonzales.ledgerrail.transfer;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class LedgerRepository {

    private final NamedParameterJdbcTemplate jdbc;

    LedgerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(
            UUID id,
            UUID transferId,
            String accountCode,
            LedgerEntryType entryType,
            BigDecimal amount,
            String currency,
            Instant createdAt) {
        String sql = """
                INSERT INTO ledger_entries (
                    id, transfer_id, account_code, entry_type, amount, currency, created_at
                ) VALUES (
                    :id, :transferId, :accountCode, :entryType, :amount, :currency, :createdAt
                )
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("transferId", transferId)
                .addValue("accountCode", accountCode)
                .addValue("entryType", entryType.name())
                .addValue("amount", amount)
                .addValue("currency", currency)
                .addValue("createdAt", createdAt.atOffset(ZoneOffset.UTC));
        jdbc.update(sql, parameters);
    }

    List<LedgerEntryResponse> findByTransferId(UUID transferId) {
        String sql = """
                SELECT * FROM ledger_entries
                WHERE transfer_id = :transferId
                ORDER BY created_at, entry_type
                """;
        return jdbc.query(sql, Map.of("transferId", transferId), this::map);
    }

    private LedgerEntryResponse map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new LedgerEntryResponse(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("transfer_id", UUID.class),
                resultSet.getString("account_code"),
                LedgerEntryType.valueOf(resultSet.getString("entry_type")),
                resultSet.getBigDecimal("amount"),
                resultSet.getString("currency"),
                resultSet.getTimestamp("created_at").toInstant());
    }
}
