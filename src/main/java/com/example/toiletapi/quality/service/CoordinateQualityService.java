package com.example.toiletapi.quality.service;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.quality.dto.CoordinateQualityReportResponse;
import com.example.toiletapi.quality.dto.CoordinateQualityRevisionResponse;
import com.example.toiletapi.quality.dto.CorrectToiletCoordinateRequest;
import com.example.toiletapi.quality.dto.DuplicateCoordinateGroupDetailResponse;
import com.example.toiletapi.quality.dto.DuplicateCoordinateGroupPageResponse;
import com.example.toiletapi.quality.dto.DuplicateCoordinateGroupResponse;
import com.example.toiletapi.quality.dto.DuplicateCoordinateToiletResponse;
import com.example.toiletapi.quality.dto.ReviewCoordinateGroupRequest;
import com.example.toiletapi.quality.model.CoordinateQualityReview;
import com.example.toiletapi.quality.model.CoordinateQualityStatus;
import com.example.toiletapi.quality.repository.CoordinateQualityReviewRepository;
import com.example.toiletapi.report.model.CoordinateRevision;
import com.example.toiletapi.report.model.ReportStatus;
import com.example.toiletapi.report.repository.CoordinateRevisionRepository;
import com.example.toiletapi.report.repository.ToiletReportRepository;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CoordinateQualityService {
    private static final String DUPLICATE_GROUPS = """
            WITH duplicate_groups AS (
                SELECT latitude, longitude, COUNT(*) AS toilet_count,
                       MIN(COALESCE(name, '이름 없는 화장실')) AS representative_name,
                       MIN(COALESCE(NULLIF(road_address, ''), NULLIF(jibun_address, ''), '주소 정보 없음')) AS representative_address
                  FROM toilet
                 WHERE latitude IS NOT NULL AND longitude IS NOT NULL
                 GROUP BY latitude, longitude
                HAVING COUNT(*) > 1
            ), pending_reports AS (
                SELECT t.latitude, t.longitude, COUNT(*) AS pending_report_count
                  FROM toilet_report r
                  JOIN toilet t ON t.toilet_id = r.toilet_id
                 WHERE r.status = 'PENDING' AND r.report_type = 'COORDINATE_CORRECTION'
                 GROUP BY t.latitude, t.longitude
            )
            """;

    private static final String GROUP_SELECT = """
            SELECT SHA2(CONCAT(CAST(d.latitude AS CHAR), '|', CAST(d.longitude AS CHAR)), 256) AS group_key,
                   d.latitude, d.longitude, d.toilet_count, d.representative_name,
                   d.representative_address,
                   COALESCE(q.status, 'PENDING') AS review_status,
                   COALESCE(p.pending_report_count, 0) AS pending_report_count
              FROM duplicate_groups d
              LEFT JOIN coordinate_quality_review q
                ON q.group_key = SHA2(CONCAT(CAST(d.latitude AS CHAR), '|', CAST(d.longitude AS CHAR)), 256)
              LEFT JOIN pending_reports p ON p.latitude = d.latitude AND p.longitude = d.longitude
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final CoordinateQualityReviewRepository reviewRepository;
    private final ToiletRepository toiletRepository;
    private final ToiletReportRepository reportRepository;
    private final CoordinateRevisionRepository revisionRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public DuplicateCoordinateGroupPageResponse search(String keyword, CoordinateQualityStatus status, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        MapSqlParameterSource parameters = filters(normalizedKeyword, status)
                .addValue("limit", safeSize)
                .addValue("offset", safePage * safeSize);
        String where = filterSql();
        List<DuplicateCoordinateGroupResponse> items = jdbc.query(
                DUPLICATE_GROUPS + GROUP_SELECT + where
                        + " ORDER BY d.toilet_count DESC, d.representative_name ASC LIMIT :limit OFFSET :offset",
                parameters, (rs, rowNumber) -> mapGroup(rs));
        Long total = jdbc.queryForObject(
                DUPLICATE_GROUPS + "SELECT COUNT(*) FROM (" + GROUP_SELECT + where + ") filtered_groups",
                parameters, Long.class);
        long totalElements = total == null ? 0 : total;
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);
        return new DuplicateCoordinateGroupPageResponse(items, safePage, safeSize, totalElements, totalPages);
    }

    @Transactional(readOnly = true)
    public DuplicateCoordinateGroupDetailResponse detail(String groupKey) {
        DuplicateCoordinateGroupResponse group = findGroup(groupKey);
        MapSqlParameterSource coordinates = new MapSqlParameterSource()
                .addValue("latitude", group.latitude()).addValue("longitude", group.longitude());
        List<DuplicateCoordinateToiletResponse> toilets = jdbc.query("""
                SELECT toilet_id, mng_no, name, toilet_type, road_address, jibun_address,
                       latitude, longitude, coordinate_source
                  FROM toilet
                 WHERE latitude = :latitude AND longitude = :longitude
                 ORDER BY name ASC, toilet_id ASC
                """, coordinates, (rs, rowNumber) -> new DuplicateCoordinateToiletResponse(
                rs.getLong("toilet_id"), rs.getString("mng_no"), rs.getString("name"),
                rs.getString("toilet_type"), rs.getString("road_address"), rs.getString("jibun_address"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("coordinate_source")));
        List<Long> toiletIds = toilets.stream().map(DuplicateCoordinateToiletResponse::id).toList();
        Map<Long, String> names = toilets.stream().collect(java.util.stream.Collectors.toMap(
                DuplicateCoordinateToiletResponse::id, item -> Objects.toString(item.name(), "이름 없는 화장실")));
        List<CoordinateQualityReportResponse> reports = reportRepository
                .findByToiletIdInAndStatusAndReportTypeOrderByCreatedAtAsc(toiletIds, ReportStatus.PENDING, "COORDINATE_CORRECTION")
                .stream().map(report -> new CoordinateQualityReportResponse(report.getId(), report.getToiletId(),
                        names.get(report.getToiletId()), report.getCreatedAt())).toList();
        List<CoordinateQualityRevisionResponse> revisions = revisionRepository
                .findByToiletIdInOrderByAppliedAtDesc(toiletIds).stream().map(this::revisionResponse).toList();
        return new DuplicateCoordinateGroupDetailResponse(group, toilets, reports, revisions);
    }

    public DuplicateCoordinateGroupResponse reviewGroup(Long adminId, String groupKey, ReviewCoordinateGroupRequest request) {
        DuplicateCoordinateGroupResponse group = findGroup(groupKey);
        if (group.latitude().compareTo(request.latitude()) != 0 || group.longitude().compareTo(request.longitude()) != 0) {
            throw new IllegalArgumentException("검토 대상 좌표가 변경되었습니다. 목록을 새로고침해 주세요.");
        }
        CoordinateQualityReview review = reviewRepository.findById(groupKey)
                .orElseGet(() -> CoordinateQualityReview.create(groupKey, group.latitude(), group.longitude()));
        review.review(request.status(), trim(request.note()), adminId);
        reviewRepository.save(review);
        auditLogService.record(adminId, AuditAction.COORDINATE_GROUP_REVIEWED, "COORDINATE_GROUP", null,
                Map.of("groupKey", groupKey, "status", request.status().name(), "toiletCount", group.toiletCount()));
        return new DuplicateCoordinateGroupResponse(group.groupKey(), group.latitude(), group.longitude(), group.toiletCount(),
                group.representativeName(), group.region(), request.status(), group.pendingReportCount());
    }

    public DuplicateCoordinateToiletResponse correctToilet(Long adminId, Long toiletId, CorrectToiletCoordinateRequest request) {
        Toilet toilet = toiletRepository.findByIdForUpdate(toiletId)
                .orElseThrow(() -> new IllegalArgumentException("화장실을 찾을 수 없습니다."));
        CoordinateRevision revision = CoordinateRevision.createAdminDirect(toiletId, toilet.getLatitude(), toilet.getLongitude(),
                toilet.getRoadAddress(), request.latitude(), request.longitude(), request.roadAddress().trim(), adminId);
        toilet.applyAdminConfirmedCoordinates(request.latitude(), request.longitude(), request.roadAddress().trim());
        revisionRepository.save(revision);
        auditLogService.record(adminId, AuditAction.TOILET_COORDINATE_CORRECTED, "TOILET", toiletId,
                Map.of("source", "ADMIN_DIRECT", "reviewNote", Objects.toString(trim(request.note()), "")));
        return toiletResponse(toilet);
    }

    private DuplicateCoordinateGroupResponse findGroup(String groupKey) {
        MapSqlParameterSource parameters = filters("", null).addValue("groupKey", groupKey);
        List<DuplicateCoordinateGroupResponse> groups = jdbc.query(
                DUPLICATE_GROUPS + GROUP_SELECT + " WHERE SHA2(CONCAT(CAST(d.latitude AS CHAR), '|', CAST(d.longitude AS CHAR)), 256) = :groupKey",
                parameters, (rs, rowNumber) -> mapGroup(rs));
        if (groups.isEmpty()) throw new IllegalArgumentException("중복 좌표 그룹을 찾을 수 없습니다.");
        return groups.getFirst();
    }

    private MapSqlParameterSource filters(String keyword, CoordinateQualityStatus status) {
        return new MapSqlParameterSource()
                .addValue("keyword", keyword)
                .addValue("keywordPattern", "%" + keyword + "%")
                .addValue("status", status == null ? null : status.name());
    }

    private String filterSql() {
        return """
                 WHERE (:status IS NULL OR COALESCE(q.status, 'PENDING') = :status)
                   AND (:keyword = '' OR d.representative_name LIKE :keywordPattern
                        OR d.representative_address LIKE :keywordPattern
                        OR EXISTS (SELECT 1 FROM toilet t
                                    WHERE t.latitude = d.latitude AND t.longitude = d.longitude
                                      AND (t.name LIKE :keywordPattern OR t.mng_no LIKE :keywordPattern)))
                """;
    }

    private DuplicateCoordinateGroupResponse mapGroup(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DuplicateCoordinateGroupResponse(rs.getString("group_key"), rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude"), rs.getLong("toilet_count"), rs.getString("representative_name"),
                rs.getString("representative_address"), CoordinateQualityStatus.valueOf(rs.getString("review_status")),
                rs.getLong("pending_report_count"));
    }

    private DuplicateCoordinateToiletResponse toiletResponse(Toilet toilet) {
        return new DuplicateCoordinateToiletResponse(toilet.getId(), toilet.getManagementNumber(), toilet.getName(),
                toilet.getToiletType(), toilet.getRoadAddress(), toilet.getJibunAddress(), toilet.getLatitude(),
                toilet.getLongitude(), toilet.getCoordinateSource());
    }

    private CoordinateQualityRevisionResponse revisionResponse(CoordinateRevision revision) {
        return new CoordinateQualityRevisionResponse(revision.getId(), revision.getToiletId(), revision.getReportId(),
                revision.getPreviousLatitude(), revision.getPreviousLongitude(), revision.getAppliedLatitude(),
                revision.getAppliedLongitude(), revision.getAppliedRoadAddress(), revision.getAppliedByUserId(),
                revision.getAppliedAt(), revision.getSource());
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
