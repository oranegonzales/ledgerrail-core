package dev.oranegonzales.ledgerrail.security;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class DemoUsageRepository {

    private final NamedParameterJdbcTemplate jdbc;

    DemoUsageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean tryAcquireWrite(LocalDate usageDate, int dailyLimit, Instant now) {
        String sql = """
                INSERT INTO demo_usage_daily (usage_date, writes, updated_at)
                VALUES (:usageDate, 1, :updatedAt)
                ON CONFLICT (usage_date) DO UPDATE
                SET writes = demo_usage_daily.writes + 1,
                    updated_at = EXCLUDED.updated_at
                WHERE demo_usage_daily.writes < :dailyLimit
                RETURNING writes
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("usageDate", usageDate)
                .addValue("dailyLimit", dailyLimit)
                .addValue("updatedAt", now.atOffset(ZoneOffset.UTC));
        List<Integer> result = jdbc.query(sql, parameters, (row, rowNumber) -> row.getInt("writes"));
        return !result.isEmpty();
    }

    void releaseWrite(LocalDate usageDate, Instant now) {
        String sql = """
                UPDATE demo_usage_daily
                SET writes = GREATEST(writes - 1, 0),
                    updated_at = :updatedAt
                WHERE usage_date = :usageDate
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("usageDate", usageDate)
                .addValue("updatedAt", now.atOffset(ZoneOffset.UTC)));
    }
}
