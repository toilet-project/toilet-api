package com.example.toiletapi.analytics;

import java.sql.Date;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AnalyticsRepository {

    private static final List<String> DIMENSIONS = List.of(
            "PAGE", "CHANNEL", "SOURCE", "DEVICE", "OS", "BROWSER", "COUNTRY", "CITY",
            "EVENT", "EVENT_DETAIL", "RESULT_BUCKET");
    private final JdbcTemplate jdbc;

    public AnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(EventRow value) {
        jdbc.update("""
                INSERT INTO service_analytics_event(
                    occurred_at, occurred_date, event_name, page_key, channel_key, source_key,
                    device_type, os_family, browser_family, country_code, city_name, visitor_hash,
                    session_hash, engagement_seconds, result_count_bucket, event_detail,
                    success_status, new_visitor, key_event
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, Timestamp.from(value.occurredAt()), Date.valueOf(value.occurredDate()), value.eventName(),
                value.pageKey(), value.channel(), value.source(), value.device(), value.os(), value.browser(),
                value.country(), value.city(), value.visitorHash(), value.sessionHash(), value.engagementSeconds(),
                value.resultBucket(), value.eventDetail(), value.success(), value.newVisitor(), value.keyEvent());
    }

    public long startRun(Instant now, LocalDate start, LocalDate end) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO service_analytics_batch_run(started_at, range_start, range_end, status)
                    VALUES(?,?,?,'RUNNING')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setDate(2, Date.valueOf(start));
            statement.setDate(3, Date.valueOf(end));
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public void completeRun(long runId, Instant finishedAt, int dates) {
        jdbc.update("""
                UPDATE service_analytics_batch_run
                   SET finished_at=?, status='SUCCESS', processed_dates=?, error_code=NULL
                 WHERE run_id=?
                """, Timestamp.from(finishedAt), dates, runId);
    }

    public void failRun(long runId, Instant finishedAt, String code) {
        jdbc.update("""
                UPDATE service_analytics_batch_run
                   SET finished_at=?, status='FAILED', error_code=?
                 WHERE run_id=?
                """, Timestamp.from(finishedAt), code, runId);
    }

    @Transactional
    public void aggregate(LocalDate date, Instant calculatedAt, boolean finalized) {
        Date sqlDate = Date.valueOf(date);
        jdbc.update("DELETE FROM service_analytics_daily_dimension WHERE analytics_date=?", sqlDate);
        jdbc.update("DELETE FROM service_analytics_daily_summary WHERE analytics_date=?", sqlDate);
        jdbc.update("""
                INSERT INTO service_analytics_daily_summary(
                    analytics_date, active_users, new_users, sessions, views, engaged_sessions,
                    key_events, event_count, total_engagement_seconds, calculated_at, finalized
                )
                SELECT ?, COUNT(DISTINCT visitor_hash),
                       COUNT(DISTINCT CASE WHEN new_visitor THEN visitor_hash END),
                       COUNT(DISTINCT session_hash), COALESCE(SUM(event_name='page_view'),0),
                       COUNT(DISTINCT CASE WHEN event_name='engagement' AND engagement_seconds>=10 THEN session_hash END),
                       COALESCE(SUM(key_event),0), COUNT(*), COALESCE(SUM(engagement_seconds),0), ?, ?
                  FROM service_analytics_event
                 WHERE occurred_date=?
                """, sqlDate, Timestamp.from(calculatedAt), finalized, sqlDate);
        for (String type : DIMENSIONS) insertDimension(date, type);
    }

    private void insertDimension(LocalDate date, String type) {
        String expression = switch (type) {
            case "PAGE" -> "page_key";
            case "CHANNEL" -> "channel_key";
            case "SOURCE" -> "source_key";
            case "DEVICE" -> "device_type";
            case "OS" -> "os_family";
            case "BROWSER" -> "browser_family";
            case "COUNTRY" -> "country_code";
            case "CITY" -> "city_name";
            case "EVENT" -> "event_name";
            case "EVENT_DETAIL" -> "CASE WHEN event_detail='' THEN event_name ELSE CONCAT(event_name, ':', event_detail) END";
            case "RESULT_BUCKET" -> "result_count_bucket";
            default -> throw new IllegalArgumentException("Unknown analytics dimension");
        };
        String filter = "RESULT_BUCKET".equals(type) ? " AND result_count_bucket<>''" : "";
        String sql = """
                INSERT INTO service_analytics_daily_dimension(
                    analytics_date, dimension_type, dimension_key, dimension_label,
                    active_users, views, sessions, event_count, key_events, engagement_seconds
                )
                SELECT occurred_date, ?, %s, %s, COUNT(DISTINCT visitor_hash),
                       COALESCE(SUM(event_name='page_view'),0), COUNT(DISTINCT session_hash), COUNT(*),
                       COALESCE(SUM(key_event),0), COALESCE(SUM(engagement_seconds),0)
                  FROM service_analytics_event
                 WHERE occurred_date=?%s
                 GROUP BY occurred_date, %s
                """.formatted(expression, expression, filter, expression);
        jdbc.update(sql, type, Date.valueOf(date));
    }

    public void deleteExpiredEvents(LocalDate before) {
        jdbc.update("DELETE FROM service_analytics_event WHERE occurred_date < ?", Date.valueOf(before));
    }

    public record EventRow(Instant occurredAt, LocalDate occurredDate, String eventName, String pageKey,
                           String channel, String source, String device, String os, String browser,
                           String country, String city, byte[] visitorHash, byte[] sessionHash,
                           int engagementSeconds, String resultBucket, String eventDetail,
                           Boolean success, boolean newVisitor, boolean keyEvent) { }
}
