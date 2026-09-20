package com.example.toiletapi.toilet.openinghours;

import static com.example.toiletapi.toilet.openinghours.OpeningHoursModels.Normalized;
import static com.example.toiletapi.toilet.openinghours.OpeningHoursModels.ReviewItem;
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

    private static ReviewItem reviewItem(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        Object open24h = resultSet.getObject("is_open_24h");
        Object confidence = resultSet.getObject("confidence");
        return new ReviewItem(resultSet.getLong("toilet_id"), resultSet.getString("name"),
                resultSet.getString("mng_no"), resultSet.getString("road_address"),
                resultSet.getString("jibun_address"), resultSet.getString("open_time"),
                resultSet.getString("open_time_detail"), resultSet.getString("opening_policy"),
                open24h == null ? null : resultSet.getBoolean("is_open_24h"),
                resultSet.getString("normalization_status") == null
                        ? "NOT_NORMALIZED" : resultSet.getString("normalization_status"),
                confidence == null ? null : resultSet.getDouble("confidence"),
                resultSet.getString("parser_version"), resultSet.getString("holiday_policy"),
                resultSet.getBoolean("manual_override"), resultSet.getBoolean("source_changed"));
    }

    public List<PatternRow> patterns() {
        return jdbc.query("""
                SELECT TRIM(t.open_time) AS open_time,TRIM(t.open_time_detail) AS open_time_detail,COUNT(*) AS facility_count,
                       SUM(CASE WHEN oh.manual_override=TRUE THEN 1 ELSE 0 END) AS protected_count,
                       SUM(CASE WHEN oh.manual_override IS NULL OR oh.manual_override=FALSE THEN 1 ELSE 0 END) AS target_count,
                       SUM(CASE WHEN oh.source_changed=TRUE THEN 1 ELSE 0 END) AS source_changed_count,
                       MIN(t.name) AS sample_name
                  FROM toilet t LEFT JOIN toilet_opening_hours oh ON oh.toilet_id=t.toilet_id
                 WHERE t.visibility_status='VISIBLE'
                 GROUP BY TRIM(t.open_time),TRIM(t.open_time_detail)
                 ORDER BY facility_count DESC,TRIM(t.open_time),TRIM(t.open_time_detail)
                """, Map.of(), (resultSet, rowNumber) -> new PatternRow(
                resultSet.getString("open_time"), resultSet.getString("open_time_detail"),
                resultSet.getLong("facility_count"), resultSet.getLong("target_count"),
                resultSet.getLong("protected_count"), resultSet.getLong("source_changed_count"),
                resultSet.getString("sample_name")));
    }

    public List<ReviewItem> patternMembers(String openTime, String openTimeDetail, int limit) {
        var parameters = patternParameters(openTime, openTimeDetail).addValue("limit", limit);
        return jdbc.query("""
                SELECT t.toilet_id,t.name,t.mng_no,t.road_address,t.jibun_address,t.open_time,t.open_time_detail,
                       oh.opening_policy,oh.is_open_24h,oh.normalization_status,oh.confidence,
                       oh.parser_version,oh.holiday_policy,oh.manual_override,oh.source_changed
                  FROM toilet t LEFT JOIN toilet_opening_hours oh ON oh.toilet_id=t.toilet_id
                 WHERE t.visibility_status='VISIBLE'
                   AND (TRIM(t.open_time)=:openTime OR (t.open_time IS NULL AND :openTime IS NULL))
                   AND (TRIM(t.open_time_detail)=:openTimeDetail OR (t.open_time_detail IS NULL AND :openTimeDetail IS NULL))
                 ORDER BY COALESCE(oh.manual_override,FALSE),t.toilet_id
                 LIMIT :limit
                """, parameters, (resultSet, rowNumber) -> reviewItem(resultSet));
    }

    public List<RawSource> patternTargets(String openTime, String openTimeDetail) {
        return jdbc.query("""
                SELECT t.toilet_id,t.open_time,t.open_time_detail
                  FROM toilet t LEFT JOIN toilet_opening_hours oh ON oh.toilet_id=t.toilet_id
                 WHERE t.visibility_status='VISIBLE'
                   AND (TRIM(t.open_time)=:openTime OR (t.open_time IS NULL AND :openTime IS NULL))
                   AND (TRIM(t.open_time_detail)=:openTimeDetail OR (t.open_time_detail IS NULL AND :openTimeDetail IS NULL))
                   AND (oh.manual_override IS NULL OR oh.manual_override=FALSE)
                 ORDER BY t.toilet_id
                """, patternParameters(openTime, openTimeDetail),
                (resultSet, rowNumber) -> new RawSource(resultSet.getLong("toilet_id"),
                        resultSet.getString("open_time"), resultSet.getString("open_time_detail")));
    }

    public Optional<View> latestPatternConfirmation(String openTime, String openTimeDetail) {
        List<Long> toiletIds = jdbc.query("""
                SELECT t.toilet_id
                  FROM toilet t JOIN toilet_opening_hours oh ON oh.toilet_id=t.toilet_id
                 WHERE t.visibility_status='VISIBLE'
                   AND (TRIM(t.open_time)=:openTime OR (t.open_time IS NULL AND :openTime IS NULL))
                   AND (TRIM(t.open_time_detail)=:openTimeDetail OR (t.open_time_detail IS NULL AND :openTimeDetail IS NULL))
                   AND oh.manual_override=TRUE
                 ORDER BY oh.confirmed_at DESC,t.toilet_id DESC
                 LIMIT 1
                """, patternParameters(openTime, openTimeDetail),
                (resultSet, rowNumber) -> resultSet.getLong("toilet_id"));
        return toiletIds.stream().findFirst().flatMap(this::find);
    }

    private static MapSqlParameterSource patternParameters(String openTime, String openTimeDetail) {
        return new MapSqlParameterSource().addValue("openTime", openTime).addValue("openTimeDetail", openTimeDetail);
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
    public record PatternRow(String openTime, String openTimeDetail, long facilityCount, long targetCount,
                             long protectedCount, long sourceChangedCount, String sampleName) {}
    public record CurrentState(String sourceHash, boolean manualOverride, String parserVersion) {}
    private record Header(String openingPolicy, Boolean open24h, String status, Double confidence,
                          String parserVersion, String holidayPolicy, boolean manualOverride,
                          boolean sourceChanged) {}
}
