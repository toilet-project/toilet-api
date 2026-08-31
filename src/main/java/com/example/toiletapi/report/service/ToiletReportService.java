package com.example.toiletapi.report.service;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.report.dto.*;
import com.example.toiletapi.report.model.*;
import com.example.toiletapi.report.repository.*;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.math.BigDecimal; import java.nio.charset.StandardCharsets; import java.security.MessageDigest; import java.util.*;
import lombok.RequiredArgsConstructor; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page; import org.springframework.data.domain.PageRequest; import org.springframework.data.domain.Pageable;

@Service @RequiredArgsConstructor @Transactional
public class ToiletReportService {
    private final ToiletReportRepository reportRepository; private final CoordinateRevisionRepository revisionRepository;
    private final ToiletRepository toiletRepository; private final AppUserRepository userRepository; private final AuditLogService auditLogService;
    public ToiletReportResponse submit(Long userId, CreateToiletReportRequest request) {
        validateRequest(request); Toilet toilet = toiletRepository.findById(request.toiletId()).orElseThrow(() -> new IllegalArgumentException("대상 화장실을 찾을 수 없습니다."));
        userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("사용자 정보를 찾을 수 없습니다."));
        String activeKey = hash(request.toiletId() + ":" + userId + ":" + request.reportType());
        if (reportRepository.existsByActiveRequestKey(activeKey)) throw new IllegalArgumentException("이미 처리 대기 중인 같은 유형의 제보가 있습니다.");
        ToiletReport report = "COORDINATE_CORRECTION".equals(request.reportType())
                ? ToiletReport.createCoordinateCorrection(request.toiletId(), userId, request.latitude(), request.longitude(), request.roadAddress().trim(), request.reason().trim(), activeKey)
                : ToiletReport.createOpenTimeCorrection(request.toiletId(), userId, request.openTime().trim(), request.reason().trim(), activeKey);
        return response(reportRepository.save(report), toilet.getName());
    }
    @Transactional(readOnly = true) public List<ToiletReportResponse> mine(Long userId) { return responses(reportRepository.findByReporterUserIdOrderByCreatedAtDesc(userId)); }
    @Transactional(readOnly = true) public List<ToiletReportResponse> pending() { return responses(reportRepository.findByStatusOrderByCreatedAtAsc(ReportStatus.PENDING)); }
    @Transactional(readOnly = true) public ToiletReportDashboardResponse pendingDashboard() {
        List<ToiletReport> recentReports = reportRepository.findTop5ByStatusOrderByCreatedAtAsc(ReportStatus.PENDING);
        return new ToiletReportDashboardResponse(reportRepository.countByStatus(ReportStatus.PENDING), listItems(recentReports));
    }
    @Transactional(readOnly = true) public ToiletReportPageResponse pendingPage(String keyword, int page, int size) {
        int safePage = Math.max(page, 0); int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<ToiletReport> reports = reportRepository.findPendingByToiletName(ReportStatus.PENDING, keyword == null ? "" : keyword.trim(), pageable);
        Map<Long, String> toiletNames = toiletNames(reports.getContent());
        return ToiletReportPageResponse.from(reports.map(report -> ToiletReportListItem.from(report, toiletNames.get(report.getToiletId()))));
    }
    @Transactional(readOnly = true) public ToiletReportDetailResponse pendingDetail(Long reportId) {
        ToiletReport report = reportRepository.findById(reportId).orElseThrow(() -> new IllegalArgumentException("제보를 찾을 수 없습니다."));
        Toilet toilet = toiletRepository.findById(report.getToiletId()).orElseThrow(() -> new IllegalArgumentException("대상 화장실을 찾을 수 없습니다."));
        return ToiletReportDetailResponse.from(response(report, toilet.getName()), toilet);
    }
    public ToiletReportResponse approve(Long adminId, Long reportId, ReviewToiletReportRequest request) {
        ToiletReport report = reportRepository.findByIdForUpdate(reportId).orElseThrow(() -> new IllegalArgumentException("제보를 찾을 수 없습니다."));
        Toilet toilet = toiletRepository.findById(report.getToiletId()).orElseThrow(() -> new IllegalArgumentException("대상 화장실을 찾을 수 없습니다."));
        if ("COORDINATE_CORRECTION".equals(report.getReportType())) {
            BigDecimal previousLatitude = toilet.getLatitude(), previousLongitude = toilet.getLongitude(); String previousRoadAddress = toilet.getRoadAddress();
            BigDecimal appliedLatitude = confirmedLatitude(request, report);
            BigDecimal appliedLongitude = confirmedLongitude(request, report);
            String appliedRoadAddress = confirmedRoadAddress(request, report);
            validateConfirmedCoordinates(appliedLatitude, appliedLongitude, appliedRoadAddress);
            toilet.applyAdminConfirmedCoordinates(appliedLatitude, appliedLongitude, appliedRoadAddress);
            revisionRepository.save(CoordinateRevision.create(report, previousLatitude, previousLongitude, previousRoadAddress,
                    appliedLatitude, appliedLongitude, appliedRoadAddress, adminId));
        } else if ("OPEN_TIME_CORRECTION".equals(report.getReportType())) {
            toilet.applyReportedOpenTime(report.getProposedOpenTime());
        } else throw new IllegalArgumentException("처리할 수 없는 제보 유형입니다.");
        report.approve(adminId, note(request));
        Map<String, Object> auditDetails = new HashMap<>(); auditDetails.put("toiletId", report.getToiletId());
        if ("COORDINATE_CORRECTION".equals(report.getReportType())) auditDetails.put("coordinateAdjustedByAdmin", hasCoordinateOverride(request));
        auditLogService.recordReportDecision(adminId, reportId, AuditAction.REPORT_APPROVED, auditDetails); return response(report, toilet.getName());
    }
    public ToiletReportResponse reject(Long adminId, Long reportId, ReviewToiletReportRequest request) {
        ToiletReport report = reportRepository.findByIdForUpdate(reportId).orElseThrow(() -> new IllegalArgumentException("제보를 찾을 수 없습니다."));
        Toilet toilet = toiletRepository.findById(report.getToiletId()).orElseThrow(() -> new IllegalArgumentException("대상 화장실을 찾을 수 없습니다."));
        report.reject(adminId, note(request));
        auditLogService.recordReportDecision(adminId, reportId, AuditAction.REPORT_REJECTED, Map.of("toiletId", report.getToiletId())); return response(report, toilet.getName());
    }
    private void validateRequest(CreateToiletReportRequest request) {
        if (request == null || request.toiletId() == null || request.reportType() == null || request.reason() == null || request.reason().isBlank()) throw new IllegalArgumentException("화장실, 제보 유형, 제보 사유는 필수입니다.");
        if (request.reason().trim().length() > 500) throw new IllegalArgumentException("제보 사유는 500자 이하여야 합니다.");
        if ("COORDINATE_CORRECTION".equals(request.reportType())) {
            if (request.latitude() == null || request.longitude() == null || request.roadAddress() == null || request.roadAddress().isBlank() || request.roadAddress().trim().length() > 255) throw new IllegalArgumentException("위치 제보에는 제안 좌표와 도로명 주소가 필요합니다.");
            if (request.latitude().compareTo(BigDecimal.valueOf(-90)) < 0 || request.latitude().compareTo(BigDecimal.valueOf(90)) > 0 || request.longitude().compareTo(BigDecimal.valueOf(-180)) < 0 || request.longitude().compareTo(BigDecimal.valueOf(180)) > 0) throw new IllegalArgumentException("제안 좌표 형식이 올바르지 않습니다.");
        } else if ("OPEN_TIME_CORRECTION".equals(request.reportType())) {
            if (request.openTime() == null || request.openTime().isBlank() || request.openTime().trim().length() > 50) throw new IllegalArgumentException("개방 시간은 50자 이하여야 합니다.");
        } else throw new IllegalArgumentException("지원하지 않는 제보 유형입니다.");
    }
    private String note(ReviewToiletReportRequest request) { return request == null || request.note() == null ? null : request.note().trim(); }
    private BigDecimal confirmedLatitude(ReviewToiletReportRequest request, ToiletReport report) { return request != null && request.confirmedLatitude() != null ? request.confirmedLatitude() : report.getProposedLatitude(); }
    private BigDecimal confirmedLongitude(ReviewToiletReportRequest request, ToiletReport report) { return request != null && request.confirmedLongitude() != null ? request.confirmedLongitude() : report.getProposedLongitude(); }
    private String confirmedRoadAddress(ReviewToiletReportRequest request, ToiletReport report) { return request != null && request.confirmedRoadAddress() != null && !request.confirmedRoadAddress().isBlank() ? request.confirmedRoadAddress().trim() : report.getProposedRoadAddress(); }
    private boolean hasCoordinateOverride(ReviewToiletReportRequest request) { return request != null && (request.confirmedLatitude() != null || request.confirmedLongitude() != null || (request.confirmedRoadAddress() != null && !request.confirmedRoadAddress().isBlank())); }
    private void validateConfirmedCoordinates(BigDecimal latitude, BigDecimal longitude, String roadAddress) {
        if (latitude == null || longitude == null || roadAddress == null || roadAddress.isBlank() || roadAddress.length() > 255
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("관리자 확정 위치 정보가 올바르지 않습니다.");
        }
    }
    private List<ToiletReportResponse> responses(List<ToiletReport> reports) {
        Map<Long, String> toiletNames = toiletNames(reports);
        return reports.stream().map(report -> response(report, toiletNames.get(report.getToiletId()))).toList();
    }
    private List<ToiletReportListItem> listItems(List<ToiletReport> reports) {
        Map<Long, String> toiletNames = toiletNames(reports);
        return reports.stream().map(report -> ToiletReportListItem.from(report, toiletNames.get(report.getToiletId()))).toList();
    }
    private Map<Long, String> toiletNames(List<ToiletReport> reports) {
        return toiletRepository.findAllById(reports.stream().map(ToiletReport::getToiletId).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(Toilet::getId, Toilet::getName));
    }
    private ToiletReportResponse response(ToiletReport report, String toiletName) { return ToiletReportResponse.from(report, toiletName); }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
}
