package com.example.toiletapi.toilet.openinghours;

import static com.example.toiletapi.toilet.openinghours.OpeningHoursModels.Normalized;
import static com.example.toiletapi.toilet.openinghours.OpeningHoursModels.Slot;
import static com.example.toiletapi.toilet.openinghours.OpeningHoursModels.View;

import java.sql.Time;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OpeningHoursRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public OpeningHoursRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CurrentState> currentState(long toiletId) {
        return jdbc.query("""
                SELECT source_hash,manual_override,parser_version
                  FROM toilet_opening_hours WHERE toilet_id=:toiletId
                """, Map.of("toiletId", toiletId), (resultSet, rowNumber) -> new CurrentState(
                resultSet.getString("source_hash"), resultSet.getBoolean("manual_override"),
                resultSet.getString("parser_version"))).stream().findFirst();
    }

    public void markManualSourceChanged(long toiletId, String sourceHash) {
        jdbc.update("""
                UPDATE toilet_opening_hours
                   SET source_changed=source_changed OR source_hash<>:sourceHash,
                       source_hash=:sourceHash,updated_at=CURRENT_TIMESTAMP
                 WHERE toilet_id=:toiletId AND manual_override=TRUE
                """, Map.of("toiletId", toiletId, "sourceHash", sourceHash));
    }

    public void saveAutomatic(long toiletId, String sourceHash, Normalized value) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("toiletId", toiletId)
                .addValue("sourceHash", sourceHash)
                .addValue("openingPolicy", value.openingPolicy())
                .addValue("open24h", value.open24h())
                .addValue("status", value.status())
                .addValue("confidence", value.confidence())
                .addValue("parserVersion", OpeningHoursParser.VERSION)
                .addValue("holidayPolicy", value.holidayPolicy());
        jdbc.update("""
                INSERT INTO toilet_opening_hours
                    (toilet_id,source_hash,opening_policy,is_open_24h,normalization_status,
                     confidence,parser_version,holiday_policy,manual_override,source_changed,
                     confirmed_by_user_id,confirmed_at,created_at,updated_at)
                VALUES
                    (:toiletId,:sourceHash,:openingPolicy,:open24h,:status,
                     :confidence,:parserVersion,:holidayPolicy,FALSE,FALSE,NULL,NULL,
                     CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE
                    source_hash=VALUES(source_hash),opening_policy=VALUES(opening_policy),
                    is_open_24h=VALUES(is_open_24h),normalization_status=VALUES(normalization_status),
                    confidence=VALUES(confidence),parser_version=VALUES(parser_version),
                    holiday_policy=VALUES(holiday_policy),source_changed=FALSE,
                    confirmed_by_user_id=NULL,confirmed_at=NULL,updated_at=VALUES(updated_at)
                """, parameters);
        replaceSchedules(toiletId, value.schedules());
    }

    public void saveManual(long adminId, long toiletId, String sourceHash, Normalized value) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("toiletId", toiletId)
                .addValue("sourceHash", sourceHash)
                .addValue("openingPolicy", value.openingPolicy())
                .addValue("open24h", value.open24h())
                .addValue("status", "CONFIRMED")
                .addValue("confidence", 1.0)
                .addValue("parserVersion", OpeningHoursParser.VERSION)
                .addValue("holidayPolicy", value.holidayPolicy())
                .addValue("adminId", adminId);
        jdbc.update("""
                INSERT INTO toilet_opening_hours
                    (toilet_id,source_hash,opening_policy,is_open_24h,normalization_status,
                     confidence,parser_version,holiday_policy,manual_override,source_changed,
                     confirmed_by_user_id,confirmed_at,created_at,updated_at)
                VALUES
                    (:toiletId,:sourceHash,:openingPolicy,:open24h,:status,
                     :confidence,:parserVersion,:holidayPolicy,TRUE,FALSE,:adminId,CURRENT_TIMESTAMP,
                     CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE
                    source_hash=VALUES(source_hash),opening_policy=VALUES(opening_policy),
                    is_open_24h=VALUES(is_open_24h),normalization_status='CONFIRMED',
                    confidence=1.0,parser_version=VALUES(parser_version),holiday_policy=VALUES(holiday_policy),
                    manual_override=TRUE,source_changed=FALSE,confirmed_by_user_id=VALUES(confirmed_by_user_id),
                    confirmed_at=VALUES(confirmed_at),updated_at=VALUES(updated_at)
                """, parameters);
        replaceSchedules(toiletId, value.schedules());
    }

    private void replaceSchedules(long toiletId, List<Slot> schedules) {
        jdbc.update("DELETE FROM toilet_opening_schedule WHERE toilet_id=:toiletId", Map.of("toiletId", toiletId));
        for (Slot slot : schedules) {
            jdbc.update("""
                    INSERT INTO toilet_opening_schedule
                        (toilet_id,day_of_week,slot_index,start_time,end_time,crosses_midnight,is_closed)
                    VALUES
                        (:toiletId,:dayOfWeek,:slotIndex,:startTime,:endTime,:crossesMidnight,:closed)
                    """, new MapSqlParameterSource()
                    .addValue("toiletId", toiletId)
                    .addValue("dayOfWeek", slot.dayOfWeek())
                    .addValue("slotIndex", slot.slotIndex())
                    .addValue("startTime", slot.startTime() == null ? null : Time.valueOf(slot.startTime()))
                    .addValue("endTime", slot.endTime() == null ? null : Time.valueOf(slot.endTime()))
                    .addValue("crossesMidnight", slot.crossesMidnight())
                    .addValue("closed", slot.closed()));
        }
    }

    public Optional<View> find(long toiletId) {
        List<Header> values = jdbc.query("""
                SELECT opening_policy,is_open_24h,normalization_status,confidence,parser_version,
                       holiday_policy,manual_override,source_changed
                  FROM toilet_opening_hours WHERE toilet_id=:toiletId
                """, Map.of("toiletId", toiletId), (resultSet, rowNumber) -> {
            Object open24h = resultSet.getObject("is_open_24h");
            return new Header(
                    resultSet.getString("opening_policy"),
                    open24h == null ? null : resultSet.getBoolean("is_open_24h"),
                    resultSet.getString("normalization_status"),
                    resultSet.getObject("confidence") == null ? null : resultSet.getDouble("confidence"),
                    resultSet.getString("parser_version"),
                    resultSet.getString("holiday_policy"),
                    resultSet.getBoolean("manual_override"),
                    resultSet.getBoolean("source_changed"));
        });
        return values.stream().findFirst().map(value -> new View(value.openingPolicy(), value.open24h(),
                value.status(), value.confidence(), value.parserVersion(), value.holidayPolicy(),
                value.manualOverride(), value.sourceChanged(), schedules(toiletId)));
    }

    private List<Slot> schedules(long toiletId) {
        return jdbc.query("""
                SELECT day_of_week,slot_index,start_time,end_time,crosses_midnight,is_closed
                  FROM toilet_opening_schedule
                 WHERE toilet_id=:toiletId ORDER BY day_of_week,slot_index
                """, Map.of("toiletId", toiletId), (resultSet, rowNumber) -> new Slot(
                resultSet.getInt("day_of_week"), resultSet.getInt("slot_index"),
                resultSet.getTime("start_time") == null ? null : resultSet.getTime("start_time").toLocalTime(),
                resultSet.getTime("end_time") == null ? null : resultSet.getTime("end_time").toLocalTime(),
                resultSet.getBoolean("crosses_midnight"), resultSet.getBoolean("is_closed")));
    }

    public List<RawSource> sourcesAfter(long afterId, int limit) {
        return jdbc.query("""
                SELECT toilet_id,open_time,open_time_detail FROM toilet
                 WHERE toilet_id>:afterId ORDER BY toilet_id LIMIT :limit
                """, new MapSqlParameterSource("afterId", afterId).addValue("limit", limit),
                (resultSet, rowNumber) -> new RawSource(resultSet.getLong("toilet_id"),
                        resultSet.getString("open_time"), resultSet.getString("open_time_detail")));
    }

    public Optional<RawSource> source(long toiletId) {
        return jdbc.query("""
                SELECT toilet_id,open_time,open_time_detail FROM toilet WHERE toilet_id=:toiletId
                """, Map.of("toiletId", toiletId),
                (resultSet, rowNumber) -> new RawSource(resultSet.getLong("toilet_id"),
                        resultSet.getString("open_time"), resultSet.getString("open_time_detail")))
                .stream().findFirst();
    }

    public record RawSource(long toiletId, String openTime, String openTimeDetail) {}
    public record CurrentState(String sourceHash, boolean manualOverride, String parserVersion) {}
    private record Header(String openingPolicy, Boolean open24h, String status, Double confidence,
                          String parserVersion, String holidayPolicy, boolean manualOverride,
                          boolean sourceChanged) {}
}
