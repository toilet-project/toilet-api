package com.example.toiletapi.report.service;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.geocoding.CoordinateAddressResolver;
import com.example.toiletapi.notification.service.UserNotificationService;
import com.example.toiletapi.report.model.ReportStatus;
import com.example.toiletapi.report.model.ToiletReport;
import com.example.toiletapi.report.repository.ToiletReportRepository;
import com.example.toiletapi.toilet.openinghours.OpeningHoursModels;
import com.example.toiletapi.toilet.openinghours.OpeningHoursService;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import com.example.toiletapi.toilet.translation.ToiletTranslationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Report decisions and real facility changes share one transaction; original evidence is immutable. */
@Service @RequiredArgsConstructor
public class ReportResolutionService {
    private final ToiletReportRepository reports;
    private final ToiletRepository toilets;
    private final JdbcTemplate jdbc;
    private final OpeningHoursService hours;
    private final CoordinateAddressResolver resolver;
    private final ToiletTranslationService translations;
    private final AuditLogService audit;
    private final UserNotificationService notifications;
    private final ObjectMapper mapper;
    public enum Action { HIDE_TEMPORARILY, RESTORE, UPDATE_OPENING_HOURS, UPDATE_COORDINATES }
    public record Request(Action action, String reason, String expectedState, String requestId,
                          BigDecimal latitude, BigDecimal longitude, OpeningHoursModels.ConfirmRequest openingHours) {}
    public record State(boolean actionable, String reportStatus, Map<String, Object> facility,
                        OpeningHoursModels.View openingHours, String expectedState, List<Map<String, Object>> history) {}
    public static class Conflict extends RuntimeException { public Conflict(String message) { super(message); } }

    @Transactional(readOnly = true)
    public State read(long reportId) {
        return state(reports.findById(reportId).orElseThrow(() -> new IllegalArgumentException("제보를 찾을 수 없습니다.")));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public State apply(long adminId, long reportId, Request request) {
        validate(request);
        var report = reports.findByIdForUpdate(reportId).orElseThrow(() -> new IllegalArgumentException("제보를 찾을 수 없습니다."));
        String requestHash = hash(request.toString());
        var prior = jdbc.queryForList("SELECT request_hash FROM toilet_report_resolution WHERE report_id=? AND request_id=?", reportId, request.requestId());
        if (!prior.isEmpty()) {
            if (!requestHash.equals(prior.getFirst().get("request_hash"))) throw new Conflict("이미 사용한 요청입니다. 내용을 다시 확인해 주세요.");
            return state(report);
        }
        if (!actionable(report)) throw new IllegalArgumentException("시설이 등록된 대기·승인 제보에서만 조치할 수 있습니다.");
        long toiletId = report.getToiletId();
        jdbc.queryForObject("SELECT toilet_id FROM toilet WHERE toilet_id=? FOR UPDATE", Long.class, toiletId);
        // Do not compare a pre-lock repeatable-read snapshot after waiting for another editor.
        // Lock the normalized row too: the separate opening-hours editor can update it directly.
        jdbc.queryForList("SELECT toilet_id FROM toilet_opening_hours WHERE toilet_id=? FOR UPDATE", toiletId);
        var before = snapshot(toiletId);
        if (!hash(json(before)).equals(request.expectedState())) throw new Conflict("다른 작업에서 시설 정보가 변경되었습니다. 최신 정보를 다시 불러와 주세요.");
        String reason = request.reason().trim();
        switch (request.action()) {
            case HIDE_TEMPORARILY, RESTORE -> changeVisibility(adminId, toiletId, request.action(), reason, before);
            case UPDATE_OPENING_HOURS -> {
                if (request.openingHours() == null) throw new IllegalArgumentException("변경할 개방시간을 입력해 주세요.");
                if ("ALWAYS".equals(request.openingHours().openingPolicy()) && !Boolean.TRUE.equals(request.openingHours().open24h()))
                    throw new IllegalArgumentException("24시간 운영 여부를 확인해 주세요.");
                hours.confirm(adminId, toiletId, request.openingHours());
            }
            case UPDATE_COORDINATES -> {
                CoordinateAddressResolver.validateCoordinates(request.latitude(), request.longitude());
                if (request.latitude().doubleValue() < 32 || request.latitude().doubleValue() > 39.5 || request.longitude().doubleValue() < 124 || request.longitude().doubleValue() > 132)
                    throw new IllegalArgumentException("대한민국 내 위치를 선택해 주세요.");
                var address = resolver.resolve(request.latitude(), request.longitude());
                var toilet = toilets.findByIdForUpdate(toiletId).orElseThrow();
                toilet.applyAdminConfirmedCoordinates(address.latitude(), address.longitude(), address.roadAddress(), address.jibunAddress());
                toilets.flush();
                translations.synchronizeKoreanSource(toiletId);
            }
        }
        var after = snapshot(toiletId);
        if (json(before).equals(json(after))) throw new IllegalArgumentException("현재 정보와 같습니다. 변경할 내용을 확인해 주세요.");
        jdbc.update("INSERT INTO toilet_report_resolution(report_id,toilet_id,actor_user_id,action,reason,request_id,request_hash,before_json,after_json) VALUES(?,?,?,?,?,?,?,?,?)",
                reportId, toiletId, adminId, request.action().name(), reason, request.requestId(), requestHash, json(before), json(after));
        audit.record(adminId, AuditAction.REPORT_FACILITY_RESOLVED, "TOILET_REPORT", reportId,
                Map.of("toiletId", toiletId, "action", request.action().name(), "reason", reason, "before", before, "after", after));
        if (report.getStatus() == ReportStatus.PENDING) {
            report.approve(adminId, reason);
            audit.recordReportDecision(adminId, reportId, AuditAction.REPORT_APPROVED, Map.of("toiletId", toiletId, "resolution", request.action().name()));
            notifications.createReportDecision(report, String.valueOf(after.get("name")));
        }
        return state(report);
    }

    static void validate(Request request) {
        if (request == null || request.action() == null || request.reason() == null || request.reason().isBlank() || request.reason().trim().length() > 500)
            throw new IllegalArgumentException("조치와 근거(1~500자)를 입력해 주세요.");
        if (request.expectedState() == null || !request.expectedState().matches("[a-f0-9]{64}") || request.requestId() == null ||
                !request.requestId().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("최신 시설 정보를 다시 불러와 주세요.");
    }

    private void changeVisibility(long adminId, long toiletId, Action action, String reason, Map<String, Object> before) {
        boolean hide = action == Action.HIDE_TEMPORARILY;
        if (!(hide ? "VISIBLE" : "HIDDEN_TEMPORARY").equals(before.get("visibilityStatus")))
            throw new Conflict(hide ? "공개 중인 시설만 임시 숨김할 수 있습니다." : "임시 숨김한 시설만 여기서 해제할 수 있습니다.");
        if (hide && jdbc.queryForObject("SELECT COUNT(*) FROM toilet WHERE representative_toilet_id=? AND visibility_status='HIDDEN_DUPLICATE'", Long.class, toiletId) > 0)
            throw new IllegalArgumentException("중복 시설의 대표입니다. 중복 관리에서 대표를 먼저 변경해 주세요.");
        jdbc.update("""
                INSERT INTO toilet_visibility_event(toilet_id,action,reason,actor_user_id,occurred_at,previous_version,
                  snapshot_name,snapshot_road_address,snapshot_jibun_address,snapshot_latitude,snapshot_longitude)
                SELECT toilet_id,?,?,?,NOW(),visibility_version,name,road_address,jibun_address,latitude,longitude FROM toilet WHERE toilet_id=?
                """, hide ? "HIDE_TEMPORARY" : "RESTORE", reason, adminId, toiletId);
        Long eventId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE toilet SET visibility_status=?,hidden_event_id=?,visibility_version=visibility_version+1 WHERE toilet_id=?",
                hide ? "HIDDEN_TEMPORARY" : "VISIBLE", hide ? eventId : null, toiletId);
        // Existing public queries and the V3 visibility cache trigger treat every non-VISIBLE row as private.
    }

    private boolean actionable(ToiletReport report) {
        return report.getToiletId() != null && (report.getStatus() == ReportStatus.PENDING || report.getStatus() == ReportStatus.APPROVED);
    }
    private State state(ToiletReport report) {
        if (report.getToiletId() == null) return new State(false, report.getStatus().name(), Map.of(), null, null, List.of());
        var snapshot = snapshot(report.getToiletId());
        var history = jdbc.queryForList("SELECT resolution_id AS id,report_id AS reportId,action,reason,actor_user_id AS actorUserId,DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s') AS createdAt,before_json AS beforeJson,after_json AS afterJson FROM toilet_report_resolution WHERE toilet_id=? ORDER BY resolution_id DESC LIMIT 50", report.getToiletId());
        return new State(actionable(report), report.getStatus().name(), snapshot, hours.find(report.getToiletId()).orElse(null), hash(json(snapshot)), history);
    }
    private Map<String, Object> snapshot(long id) {
        Map<String, Object> value = new LinkedHashMap<>();
        var row = jdbc.queryForMap("SELECT name,latitude,longitude,road_address,jibun_address,open_time,open_time_detail,visibility_status,visibility_version,hidden_event_id,coordinate_source,region_revision FROM toilet WHERE toilet_id=?", id);
        value.put("id", id); value.put("name", row.get("name"));
        value.put("latitude", row.get("latitude")); value.put("longitude", row.get("longitude"));
        value.put("roadAddress", row.get("road_address")); value.put("jibunAddress", row.get("jibun_address"));
        value.put("sourceOpenTime", row.get("open_time")); value.put("sourceOpenTimeDetail", row.get("open_time_detail"));
        value.put("visibilityStatus", row.get("visibility_status")); value.put("visibilityVersion", row.get("visibility_version"));
        value.put("hiddenEventId", row.get("hidden_event_id")); value.put("coordinateSource", row.get("coordinate_source")); value.put("regionRevision", row.get("region_revision"));
        value.put("openingHours", hours.find(id).map(view -> {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("openingPolicy", view.openingPolicy()); h.put("open24h", view.open24h()); h.put("holidayPolicy", view.holidayPolicy()); h.put("manualOverride", view.manualOverride());
            h.put("schedules", view.schedules().stream().map(slot -> {
                Map<String, Object> s = new LinkedHashMap<>(); s.put("dayOfWeek", slot.dayOfWeek()); s.put("slotIndex", slot.slotIndex());
                s.put("startTime", slot.startTime() == null ? null : slot.startTime().toString()); s.put("endTime", slot.endTime() == null ? null : slot.endTime().toString());
                s.put("closed", slot.closed()); s.put("crossesMidnight", slot.crossesMidnight()); return s;
            }).toList()); return h;
        }).orElse(null));
        return value;
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception failure) { throw new IllegalArgumentException("변경 이력을 만들지 못했습니다.", failure); } }
    static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception failure) { throw new IllegalArgumentException(failure); } }
}
