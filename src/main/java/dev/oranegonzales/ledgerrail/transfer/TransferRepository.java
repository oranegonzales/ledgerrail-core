package dev.oranegonzales.ledgerrail.transfer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class TransferRepository {

    private final NamedParameterJdbcTemplate jdbc;

    TransferRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean insert(TransferRecord transfer) {
        String sql = """
                INSERT INTO transfers (
                    id, idempotency_key, account_id, transfer_type, amount, currency, status, created_at
                ) VALUES (
                    :id, :idempotencyKey, :accountId, :transferType, :amount, :currency, :status, :createdAt
                )
                ON CONFLICT (account_id, idempotency_key) DO NOTHING
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", transfer.id())
                .addValue("idempotencyKey", transfer.idempotencyKey())
                .addValue("accountId", transfer.accountId())
                .addValue("transferType", transfer.type().name())
                .addValue("amount", transfer.amount())
                .addValue("currency", transfer.currency())
                .addValue("status", transfer.status().name())
                .addValue("createdAt", transfer.createdAt().atOffset(ZoneOffset.UTC));
        return jdbc.update(sql, parameters) == 1;
    }

    Optional<TransferRecord> findById(UUID id) {
        String sql = "SELECT * FROM transfers WHERE id = :id";
        return jdbc.query(sql, Map.of("id", id), this::map).stream().findFirst();
    }

    Optional<TransferRecord> findByAccountIdAndIdempotencyKey(UUID accountId, String idempotencyKey) {
        String sql = """
                SELECT * FROM transfers
                WHERE account_id = :accountId AND idempotency_key = :idempotencyKey
                """;
        return jdbc.query(sql, Map.of("accountId", accountId, "idempotencyKey", idempotencyKey), this::map)
                .stream()
                .findFirst();
    }

    List<TransferRecord> findByAccountId(UUID accountId) {
        String sql = """
                SELECT * FROM transfers
                WHERE account_id = :accountId
                ORDER BY created_at DESC
                """;
        return jdbc.query(sql, Map.of("accountId", accountId), this::map);
    }

    private TransferRecord map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TransferRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("idempotency_key"),
                resultSet.getObject("account_id", UUID.class),
                TransferType.valueOf(resultSet.getString("transfer_type")),
                resultSet.getBigDecimal("amount"),
                resultSet.getString("currency"),
                TransferStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").toInstant());
    }
}
