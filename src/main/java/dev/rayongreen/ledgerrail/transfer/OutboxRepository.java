package dev.rayongreen.ledgerrail.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    OutboxRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    void insertTransferCompleted(TransferRecord transfer) {
        String sql = """
                INSERT INTO outbox_events (
                    id, aggregate_id, event_type, payload, status, occurred_at
                ) VALUES (
                    :id, :aggregateId, :eventType, CAST(:payload AS jsonb), 'PENDING', :occurredAt
                )
                """;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID());
        event.put("transferId", transfer.id());
        event.put("accountId", transfer.accountId());
        event.put("type", transfer.type());
        event.put("amount", transfer.amount());
        event.put("currency", transfer.currency());
        event.put("status", transfer.status());
        event.put("occurredAt", transfer.createdAt());
        try {
            MapSqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("id", event.get("eventId"))
                    .addValue("aggregateId", transfer.id())
                    .addValue("eventType", "transfer.completed.v1")
                    .addValue("payload", objectMapper.writeValueAsString(event))
                    .addValue("occurredAt", transfer.createdAt().atOffset(ZoneOffset.UTC));
            jdbc.update(sql, parameters);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize transfer outbox event", exception);
        }
    }
}
