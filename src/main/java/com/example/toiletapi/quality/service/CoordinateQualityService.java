package com.example.toiletapi.quality.service;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.geocoding.CoordinateAddress;
import com.example.toiletapi.geocoding.CoordinateAddressResolver;
import com.example.toiletapi.quality.dto.CoordinateQualityReportResponse;
import com.example.toiletapi.quality.dto.CoordinateQualityRevisionResponse;
import com.example.toiletapi.quality.dto.CorrectToiletCoordinateRequest;
import com.example.toiletapi.quality.dto.CreateMapDisplayGroupRequest;
import com.example.toiletapi.quality.dto.DuplicateCoordinateGroupDetailResponse;
import com.example.toiletapi.quality.dto.DuplicateCoordinateGroupPageResponse;
import com.example.toiletapi.quality.dto.DuplicateCoordinateGroupResponse;
import com.example.toiletapi.quality.dto.DuplicateCoordinateToiletResponse;
import com.example.toiletapi.quality.dto.ReviewCoordinateGroupRequest;
import com.example.toiletapi.quality.dto.SaveToiletDisplayGroupRequest;
import com.example.toiletapi.quality.dto.ToiletDisplayGroupResponse;
import com.example.toiletapi.quality.model.CoordinateQualityReview;
import com.example.toiletapi.quality.model.CoordinateQualityStatus;
import com.example.toiletapi.quality.repository.CoordinateQualityReviewRepository;
import com.example.toiletapi.quality.repository.ToiletDisplayGroupRepository;
import com.example.toiletapi.report.model.CoordinateRevision;
import com.example.toiletapi.report.model.ReportStatus;
import com.example.toiletapi.report.repository.CoordinateRevisionRepository;
import com.example.toiletapi.report.repository.ToiletReportRepository;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
                SELECT t.latitude, t.longitude, COUNT(*) AS physical_count,
                       SUM(CASE WHEN g.group_id IS NULL THEN 1 ELSE 0 END) AS toilet_count,
                       MIN(CASE WHEN g.group_id IS NULL THEN COALESCE(t.name, '이름 없는 화장실') END) AS representative_name,
                       MIN(CASE WHEN g.group_id IS NULL THEN COALESCE(NULLIF(t.road_address, ''), NULLIF(t.jibun_address, ''), '주소 정보 없음') END) AS representative_address
                  FROM toilet t
                  LEFT JOIN toilet_display_group_member m ON m.toilet_id = t.toilet_id
                  LEFT JOIN toilet_display_group g ON g.group_id = m.group_id
                    AND g.latitude = t.latitude AND g.longitude = t.longitude
                 WHERE t.visibility_status='VISIBLE' AND t.latitude IS NOT NULL AND t.longitude IS NOT NULL
                 GROUP BY t.latitude, t.longitude
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
    private final CoordinateAddressResolver addressResolver;
    private final ToiletDisplayGroupRepository displayGroupRepository;

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
                        + " ORDER BY d.toilet_count DESC, d.representative_name ASC, d.latitude ASC, d.longitude ASC LIMIT :limit OFFSET :offset",
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
                SELECT t.toilet_id, t.mng_no, t.name, t.toilet_type, t.road_address, t.jibun_address,
                       t.latitude, t.longitude, t.coordinate_source, g.group_id AS display_group_id,
                       g.display_name AS display_group_name
                  FROM toilet t
                  LEFT JOIN toilet_display_group_member m ON m.toilet_id = t.toilet_id
                  LEFT JOIN toilet_display_group g ON g.group_id = m.group_id
                    AND g.latitude = t.latitude AND g.longitude = t.longitude
                 WHERE t.visibility_status='VISIBLE' AND t.latitude = :latitude AND t.longitude = :longitude
                   AND g.group_id IS NULL
                 ORDER BY COALESCE(g.display_name, t.name) ASC, m.sort_order ASC, t.toilet_id ASC
                """, coordinates, (rs, rowNumber) -> new DuplicateCoordinateToiletResponse(
                rs.getLong("toilet_id"), rs.getString("mng_no"), rs.getString("name"),
                rs.getString("toilet_type"), rs.getString("road_address"), rs.getString("jibun_address"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("coordinate_source"),
                rs.getObject("display_group_id", Long.class), rs.getString("display_group_name")));
        List<Long> toiletIds = toilets.stream().map(DuplicateCoordinateToiletResponse::id).toList();
        if (toiletIds.isEmpty()) return new DuplicateCoordinateGroupDetailResponse(group, toilets, List.of(), List.of());
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
        var address = addressResolver.resolve(request.latitude(), request.longitude());
        Toilet toilet = toiletRepository.findByIdForUpdate(toiletId)
                .orElseThrow(() -> new IllegalArgumentException("화장실을 찾을 수 없습니다."));
        CoordinateRevision revision = CoordinateRevision.createAdminDirect(toiletId, toilet.getLatitude(), toilet.getLongitude(),
                toilet.getRoadAddress(), toilet.getJibunAddress(), address.latitude(), address.longitude(), address.roadAddress(), address.jibunAddress(), adminId);
        toilet.applyAdminConfirmedCoordinates(address.latitude(), address.longitude(), address.roadAddress(), address.jibunAddress());
        if (request.displayGroupId() == null) {
            displayGroupRepository.removeToilet(toiletId);
        } else {
            joinCoordinateDisplayGroup(adminId, toiletId, address.latitude(), address.longitude(), request.displayGroupId());
        }
        revisionRepository.save(revision);
        auditLogService.record(adminId, AuditAction.TOILET_COORDINATE_CORRECTED, "TOILET", toiletId,
                Map.of("source", "ADMIN_DIRECT", "reviewNote", Objects.toString(trim(request.note()), "")));
        return toiletResponse(toilet);
    }

    private void joinCoordinateDisplayGroup(Long adminId, Long toiletId, BigDecimal latitude, BigDecimal longitude,
                                            Long displayGroupId) {
        toiletRepository.flush();
        if (!displayGroupRepository.belongsToCoordinates(displayGroupId, latitude, longitude)) {
            throw new IllegalArgumentException("선택한 위치의 관리자 확정 그룹을 찾을 수 없습니다.");
        }
        LinkedHashSet<Long> finalIds = new LinkedHashSet<>(displayGroupRepository.memberIds(displayGroupId));
        finalIds.add(toiletId);
        if (finalIds.size() > 100) throw new IllegalArgumentException("한 그룹에는 화장실을 최대 100개까지 지정할 수 있습니다.");
        List<Long> toiletIds = List.copyOf(finalIds);
        List<Long> matchingIds = displayGroupRepository.matchingToiletIds(toiletIds, latitude, longitude);
        if (!new LinkedHashSet<>(matchingIds).equals(finalIds)) {
            throw new IllegalArgumentException("관리자 확정 그룹과 같은 좌표로 보정한 화장실만 편입할 수 있습니다.");
        }
        displayGroupRepository.replaceMembers(displayGroupId, toiletIds);
        auditLogService.record(adminId, AuditAction.TOILET_DISPLAY_GROUP_SAVED, "TOILET_DISPLAY_GROUP",
                displayGroupId, Map.of("memberCount", toiletIds.size(),
                        "coordinateCorrectionToiletId", toiletId));
    }

    public ToiletDisplayGroupResponse createMapDisplayGroup(Long adminId, Long currentToiletId,
                                                             CreateMapDisplayGroupRequest request) {
        String displayName = trim(request.displayName());
        if (displayName == null) throw new IllegalArgumentException("지도에 표시할 그룹 이름을 입력해 주세요.");
        List<Long> markerToiletIds = request.markerToiletIds() == null ? List.of() : request.markerToiletIds().stream()
                .filter(Objects::nonNull).distinct().toList();
        if (markerToiletIds.isEmpty() || markerToiletIds.size() != request.markerToiletIds().size()) {
            throw new IllegalArgumentException("서로 다른 마커 화장실을 한 개 이상 선택해 주세요.");
        }
        if (markerToiletIds.contains(currentToiletId)) {
            throw new IllegalArgumentException("좌표 보정 중인 화장실은 마커 목록에서 다시 선택할 수 없습니다.");
        }

        BigDecimal requestedTargetLatitude = request.direction() == CreateMapDisplayGroupRequest.Direction.CURRENT_TO_MARKER
                ? request.markerLatitude() : request.currentLatitude();
        BigDecimal requestedTargetLongitude = request.direction() == CreateMapDisplayGroupRequest.Direction.CURRENT_TO_MARKER
                ? request.markerLongitude() : request.currentLongitude();
        var target = addressResolver.resolve(requestedTargetLatitude, requestedTargetLongitude);

        Toilet currentToilet = toiletRepository.findByIdForUpdate(currentToiletId)
                .orElseThrow(() -> new IllegalArgumentException("좌표 보정 중인 화장실을 찾을 수 없습니다."));
        if (!sameCoordinates(currentToilet, request.currentLatitude(), request.currentLongitude())) {
            throw new IllegalArgumentException("좌표 보정 중인 화장실의 위치가 변경되었습니다. 목록을 새로고침해 주세요.");
        }
        List<Long> sourceMarkerIds = displayGroupRepository.matchingToiletIds(markerToiletIds,
                request.markerLatitude(), request.markerLongitude());
        if (!new LinkedHashSet<>(sourceMarkerIds).equals(new LinkedHashSet<>(markerToiletIds))) {
            throw new IllegalArgumentException("선택한 마커 위치에 등록된 화장실만 그룹으로 만들 수 있습니다.");
        }

        List<CoordinateRevision> revisions = new ArrayList<>();
        List<Long> movedToiletIds = new ArrayList<>();
        BigDecimal targetLatitude;
        BigDecimal targetLongitude;
        if (request.direction() == CreateMapDisplayGroupRequest.Direction.CURRENT_TO_MARKER) {
            revisions.add(coordinateRevision(currentToilet, currentToiletId, target, adminId));
            currentToilet.applyAdminConfirmedCoordinates(target.latitude(), target.longitude(),
                    target.roadAddress(), target.jibunAddress());
            movedToiletIds.add(currentToiletId);
            targetLatitude = target.latitude();
            targetLongitude = target.longitude();
        } else {
            for (Long markerToiletId : markerToiletIds) {
                Toilet markerToilet = toiletRepository.findByIdForUpdate(markerToiletId)
                        .orElseThrow(() -> new IllegalArgumentException("선택한 마커 화장실을 찾을 수 없습니다."));
                revisions.add(coordinateRevision(markerToilet, markerToiletId, target, adminId));
                markerToilet.applyAdminConfirmedCoordinates(target.latitude(), target.longitude(),
                        target.roadAddress(), target.jibunAddress());
                movedToiletIds.add(markerToiletId);
            }
            targetLatitude = target.latitude();
            targetLongitude = target.longitude();
        }

        toiletRepository.flush();
        List<Long> finalToiletIds = new ArrayList<>();
        finalToiletIds.add(currentToiletId);
        finalToiletIds.addAll(markerToiletIds);
        List<Long> matchingTargetIds = displayGroupRepository.matchingToiletIds(
                finalToiletIds, targetLatitude, targetLongitude);
        if (!new LinkedHashSet<>(matchingTargetIds).equals(new LinkedHashSet<>(finalToiletIds))) {
            throw new IllegalArgumentException("선택한 방향으로 좌표를 통일하지 못했습니다.");
        }

        Long displayGroupId = displayGroupRepository.create(displayName, targetLatitude, targetLongitude, adminId);
        displayGroupRepository.replaceMembers(displayGroupId, finalToiletIds);
        revisionRepository.saveAll(revisions);
        movedToiletIds.forEach(toiletId -> auditLogService.record(adminId,
                AuditAction.TOILET_COORDINATE_CORRECTED, "TOILET", toiletId,
                Map.of("source", "ADMIN_MAP_GROUP", "direction", request.direction().name(),
                        "reviewNote", Objects.toString(trim(request.note()), ""))));
        auditLogService.record(adminId, AuditAction.TOILET_DISPLAY_GROUP_SAVED, "TOILET_DISPLAY_GROUP",
                displayGroupId, Map.of("displayName", displayName, "memberCount", finalToiletIds.size(),
                        "direction", request.direction().name(), "currentToiletId", currentToiletId));
        return new ToiletDisplayGroupResponse(displayGroupId, displayName, finalToiletIds);
    }

    private CoordinateRevision coordinateRevision(Toilet toilet, Long toiletId,
                                                  CoordinateAddress target,
                                                  Long adminId) {
        return CoordinateRevision.createAdminDirect(toiletId, toilet.getLatitude(), toilet.getLongitude(),
                toilet.getRoadAddress(), toilet.getJibunAddress(), target.latitude(), target.longitude(),
                target.roadAddress(), target.jibunAddress(), adminId);
    }

    private boolean sameCoordinates(Toilet toilet, BigDecimal latitude, BigDecimal longitude) {
        return toilet.getLatitude() != null && toilet.getLongitude() != null
                && toilet.getLatitude().compareTo(latitude) == 0
                && toilet.getLongitude().compareTo(longitude) == 0;
    }

    public ToiletDisplayGroupResponse saveDisplayGroup(Long adminId, String groupKey, SaveToiletDisplayGroupRequest request) {
        DuplicateCoordinateGroupResponse coordinateGroup = findGroup(groupKey);
        List<Long> toiletIds = request.toiletIds() == null ? List.of() : request.toiletIds().stream()
                .filter(Objects::nonNull).distinct().toList();
        if (toiletIds.size() < 2 || toiletIds.size() != request.toiletIds().size()) {
            throw new IllegalArgumentException("서로 다른 화장실을 두 개 이상 선택해 주세요.");
        }
        List<Long> matchingIds = displayGroupRepository.matchingToiletIds(toiletIds,
                coordinateGroup.latitude(), coordinateGroup.longitude());
        if (!new LinkedHashSet<>(matchingIds).equals(new LinkedHashSet<>(toiletIds))) {
            throw new IllegalArgumentException("현재 중복 좌표 그룹에 속한 화장실만 묶을 수 있습니다.");
        }

        String displayName = trim(request.displayName());
        if (displayName == null) throw new IllegalArgumentException("지도에 표시할 이름을 입력해 주세요.");
        Long displayGroupId = request.displayGroupId();
        if (displayGroupId == null) {
            displayGroupId = displayGroupRepository.create(displayName, coordinateGroup.latitude(),
                    coordinateGroup.longitude(), adminId);
        } else {
            if (!displayGroupRepository.belongsToCoordinates(displayGroupId,
                    coordinateGroup.latitude(), coordinateGroup.longitude())) {
                throw new IllegalArgumentException("현재 좌표에 속한 지도 노출 그룹을 찾을 수 없습니다.");
            }
            displayGroupRepository.update(displayGroupId, displayName, adminId);
        }
        displayGroupRepository.replaceMembers(displayGroupId, toiletIds);
        auditLogService.record(adminId, AuditAction.TOILET_DISPLAY_GROUP_SAVED, "TOILET_DISPLAY_GROUP",
                displayGroupId, Map.of("displayName", displayName, "memberCount", toiletIds.size(), "groupKey", groupKey));
        return new ToiletDisplayGroupResponse(displayGroupId, displayName, toiletIds);
    }

    public void deleteDisplayGroup(Long adminId, Long displayGroupId) {
        displayGroupRepository.delete(displayGroupId);
        auditLogService.record(adminId, AuditAction.TOILET_DISPLAY_GROUP_DELETED, "TOILET_DISPLAY_GROUP",
                displayGroupId, Map.of());
    }

    private DuplicateCoordinateGroupResponse findGroup(String groupKey) {
        MapSqlParameterSource parameters = filters("", null).addValue("groupKey", groupKey);
        List<DuplicateCoordinateGroupResponse> groups = jdbc.query(
                DUPLICATE_GROUPS + GROUP_SELECT + " WHERE SHA2(CONCAT(CAST(d.latitude AS CHAR), '|', CAST(d.longitude AS CHAR)), 256) = :groupKey",
                parameters, (rs, rowNumber) -> mapGroup(rs));
        if (groups.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "해당 좌표에 남은 검토 시설이 없습니다.");
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
                 WHERE d.physical_count > 1 AND d.toilet_count > 0
                   AND (:status IS NULL OR COALESCE(q.status, 'PENDING') = :status)
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
                toilet.getLongitude(), toilet.getCoordinateSource(), null, null);
    }

    private CoordinateQualityRevisionResponse revisionResponse(CoordinateRevision revision) {
        return new CoordinateQualityRevisionResponse(revision.getId(), revision.getToiletId(), revision.getReportId(),
                revision.getPreviousLatitude(), revision.getPreviousLongitude(), revision.getAppliedLatitude(),
                revision.getAppliedLongitude(), revision.getAppliedRoadAddress(), revision.getAppliedByUserId(),
                revision.getAppliedAt(), revision.getSource(), revision.getAppliedJibunAddress());
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
