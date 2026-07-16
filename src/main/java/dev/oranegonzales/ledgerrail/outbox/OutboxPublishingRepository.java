package dev.oranegonzales.ledgerrail.outbox;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxPublishingRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public OutboxPublishingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public List<OutboxEvent> claimBatch(int batchSize, Duration leaseTimeout, Instant now) {
        String recoverSql = """
                UPDATE outbox_events
                SET status = 'PENDING',
                    next_attempt_at = :now,
                    locked_at = NULL,
                    claim_token = NULL,
                    last_error = 'Publisher lease expired before acknowledgement'
                WHERE status = 'IN_FLIGHT'
                  AND locked_at < :staleBefore
                """;
        MapSqlParameterSource recoveryParameters = new MapSqlParameterSource()
                .addValue("now", now.atOffset(ZoneOffset.UTC))
                .addValue("staleBefore", now.minus(leaseTimeout).atOffset(ZoneOffset.UTC));
        jdbc.update(recoverSql, recoveryParameters);

        UUID claimToken = UUID.randomUUID();
        String claimSql = """
                WITH candidates AS (
                    SELECT id
                    FROM outbox_events
                    WHERE status = 'PENDING'
                      AND next_attempt_at <= :now
                    ORDER BY occurred_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                )
                UPDATE outbox_events AS event
                SET status = 'IN_FLIGHT',
                    attempts = event.attempts + 1,
                    locked_at = :now,
                    claim_token = :claimToken
                FROM candidates
                WHERE event.id = candidates.id
                RETURNING event.id,
                          event.aggregate_id,
                          event.event_type,
                          event.payload::text,
                          event.attempts,
                          event.claim_token
                """;
        MapSqlParameterSource claimParameters = new MapSqlParameterSource()
                .addValue("now", now.atOffset(ZoneOffset.UTC))
                .addValue("batchSize", batchSize)
                .addValue("claimToken", claimToken);
        return jdbc.query(claimSql, claimParameters, (resultSet, rowNumber) -> new OutboxEvent(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("aggregate_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getString("payload"),
                resultSet.getInt("attempts"),
                resultSet.getObject("claim_token", UUID.class)));
    }

    public boolean markPublished(UUID eventId, UUID claimToken, Instant publishedAt) {
        String sql = """
                UPDATE outbox_events
                SET status = 'PUBLISHED',
                    published_at = :publishedAt,
                    locked_at = NULL,
                    claim_token = NULL,
                    last_error = NULL
                WHERE id = :eventId
                  AND claim_token = :claimToken
                  AND status = 'IN_FLIGHT'
                """;
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("claimToken", claimToken)
                .addValue("publishedAt", publishedAt.atOffset(ZoneOffset.UTC))) == 1;
    }

    public boolean markForRetry(UUID eventId, UUID claimToken, Instant nextAttemptAt, String error) {
        String sql = """
                UPDATE outbox_events
                SET status = 'PENDING',
                    next_attempt_at = :nextAttemptAt,
                    locked_at = NULL,
                    claim_token = NULL,
                    last_error = :error
                WHERE id = :eventId
                  AND claim_token = :claimToken
                  AND status = 'IN_FLIGHT'
                """;
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("claimToken", claimToken)
                .addValue("nextAttemptAt", nextAttemptAt.atOffset(ZoneOffset.UTC))
                .addValue("error", error)) == 1;
    }

    public boolean markFailed(UUID eventId, UUID claimToken, String error) {
        String sql = """
                UPDATE outbox_events
                SET status = 'FAILED',
                    locked_at = NULL,
                    claim_token = NULL,
                    last_error = :error
                WHERE id = :eventId
                  AND claim_token = :claimToken
                  AND status = 'IN_FLIGHT'
                """;
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("claimToken", claimToken)
                .addValue("error", error)) == 1;
    }

    public int releaseClaim(UUID claimToken, Instant nextAttemptAt, String error) {
        String sql = """
                UPDATE outbox_events
                SET status = 'PENDING',
                    next_attempt_at = :nextAttemptAt,
                    locked_at = NULL,
                    claim_token = NULL,
                    last_error = :error
                WHERE claim_token = :claimToken
                  AND status = 'IN_FLIGHT'
                """;
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("claimToken", claimToken)
                .addValue("nextAttemptAt", nextAttemptAt.atOffset(ZoneOffset.UTC))
                .addValue("error", error));
    }

    public List<OutboxStatus> findFailed(int limit) {
        String sql = """
                SELECT id, aggregate_id, event_type, status, attempts, replay_count,
                       next_attempt_at, last_replayed_at, last_error
                FROM outbox_events
                WHERE status = 'FAILED'
                ORDER BY occurred_at
                LIMIT :limit
                """;
        return jdbc.query(sql, Map.of("limit", limit), (resultSet, rowNumber) -> mapStatus(resultSet));
    }

    public Optional<OutboxStatus> findStatus(UUID eventId) {
        String sql = """
                SELECT id, aggregate_id, event_type, status, attempts, replay_count,
                       next_attempt_at, last_replayed_at, last_error
                FROM outbox_events
                WHERE id = :eventId
                """;
        return jdbc.query(sql, Map.of("eventId", eventId), (resultSet, rowNumber) -> mapStatus(resultSet))
                .stream()
                .findFirst();
    }

    public boolean replayFailed(UUID eventId, Instant replayedAt) {
        String sql = """
                UPDATE outbox_events
                SET status = 'PENDING',
                    attempts = 0,
                    next_attempt_at = :replayedAt,
                    locked_at = NULL,
                    claim_token = NULL,
                    last_error = NULL,
                    replay_count = replay_count + 1,
                    last_replayed_at = :replayedAt
                WHERE id = :eventId
                  AND status = 'FAILED'
                """;
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("replayedAt", replayedAt.atOffset(ZoneOffset.UTC))) == 1;
    }

    private OutboxStatus mapStatus(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        java.sql.Timestamp replayedAt = resultSet.getTimestamp("last_replayed_at");
        return new OutboxStatus(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("aggregate_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getString("status"),
                resultSet.getInt("attempts"),
                resultSet.getInt("replay_count"),
                resultSet.getTimestamp("next_attempt_at").toInstant(),
                replayedAt == null ? null : replayedAt.toInstant(),
                resultSet.getString("last_error"));
    }
}
