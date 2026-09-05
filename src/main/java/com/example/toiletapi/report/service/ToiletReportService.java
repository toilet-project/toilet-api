package com.example.toiletapi.report.service;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.notification.service.UserNotificationService;
import com.example.toiletapi.geocoding.CoordinateAddress;
import com.example.toiletapi.geocoding.CoordinateAddressResolver;
import com.example.toiletapi.report.dto.*;
import com.example.toiletapi.report.model.*;
import com.example.toiletapi.report.repository.*;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.math.BigDecimal; import java.nio.charset.StandardCharsets; import java.security.MessageDigest; import java.time.LocalDate; import java.time.LocalDateTime; import java.util.*;
import lombok.RequiredArgsConstructor; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page; import org.springframework.data.domain.PageRequest; import org.springframework.data.domain.Pageable; import org.springframework.data.domain.Sort;

@Service @RequiredArgsConstructor @Transactional
public class ToiletReportService {
    private final ToiletReportRepository reportRepository; private final CoordinateRevisionRepository revisionRepository;
    private final ToiletRepository toiletRepository; private final AppUserRepository userRepository; private final AuditLogService auditLogService;
    private final UserNotificationService notificationService;
    private final CoordinateAddressResolver addressResolver;
    public ToiletReportResponse submit(Long userId, CreateToiletReportRequest request) {
        validateRequest(request); Toilet toilet = toiletRepository.findById(request.toiletId()).orElseThrow(() -> new IllegalArgumentException("대상 화장실을 찾을 수 없습니다."));
        userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("사용자 정보를 찾을 수 없습니다."));
        String activeKey = hash(request.toiletId() + ":" + userId + ":" + request.reportType());
        if (reportRepository.existsByActiveRequestKey(activeKey)) throw new IllegalArgumentException("이미 처리 대기 중인 같은 유형의 제보가 있습니다.");
        ToiletReport report;
        if ("COORDINATE_CORRECTION".equals(request.reportType())) {
            CoordinateAddress address = addressResolver.resolve(request.latitude(), request.longitude());
            report = ToiletReport.createCoordinateCorrection(request.toiletId(), userId, address.latitude(), address.longitude(),
                    address.roadAddress(), address.jibunAddress(), request.reason().trim(), activeKey);
        } else {
            report = ToiletReport.createOpenTimeCorrection(request.toiletId(), userId, request.openTime().trim(), request.reason().trim(), activeKey);
        }
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
    @Transactional(readOnly = true) public ToiletReportPageResponse searchPage(ReportStatus status, String keyword, LocalDate from, LocalDate to, String sort, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("조회 시작일은 종료일보다 늦을 수 없습니다.");
        int safePage = Math.max(page, 0); int safeSize = Math.min(Math.max(size, 1), 100);
        Sort.Direction direction = "NEWEST".equalsIgnoreCase(sort) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(direction, "createdAt"));
        LocalDateTime fromAt = from == null ? null : from.atStartOfDay();
        LocalDateTime toAt = to == null ? null : to.plusDays(1).atStartOfDay();
        Page<ToiletReport> reports = reportRepository.findByFilters(status, keyword == null ? "" : keyword.trim(), fromAt, toAt, pageable);
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
        if (report.getStatus() != ReportStatus.PENDING) throw new IllegalArgumentException("대기 중인 제보만 처리할 수 있습니다.");
        CoordinateAddress address = null;
        if ("COORDINATE_CORRECTION".equals(report.getReportType())) {
            if (request != null && (request.confirmedLatitude() == null) != (request.confirmedLongitude() == null)) {
                throw new IllegalArgumentException("관리자 확정 위도와 경도는 함께 입력해 주세요.");
            }
            address = addressResolver.resolve(confirmedLatitude(request, report), confirmedLongitude(request, report));
        }
        Toilet toilet = toiletRepository.findByIdForUpdate(report.getToiletId()).orElseThrow(() -> new IllegalArgumentException("대상 화장실을 찾을 수 없습니다."));
        if ("COORDINATE_CORRECTION".equals(report.getReportType())) {
            BigDecimal previousLatitude = toilet.getLatitude(), previousLongitude = toilet.getLongitude(); String previousRoadAddress = toilet.getRoadAddress();
            String previousJibunAddress = toilet.getJibunAddress();
            toilet.applyAdminConfirmedCoordinates(address.latitude(), address.longitude(), address.roadAddress(), address.jibunAddress());
            revisionRepository.save(CoordinateRevision.create(report, previousLatitude, previousLongitude, previousRoadAddress, previousJibunAddress,
                    address.latitude(), address.longitude(), address.roadAddress(), address.jibunAddress(), adminId));
        } else if ("OPEN_TIME_CORRECTION".equals(report.getReportType())) {
            toilet.applyReportedOpenTime(report.getProposedOpenTime());
        } else throw new IllegalArgumentException("처리할 수 없는 제보 유형입니다.");
        report.approve(adminId, note(request));
        Map<String, Object> auditDetails = new HashMap<>(); auditDetails.put("toiletId", report.getToiletId());
        if ("COORDINATE_CORRECTION".equals(report.getReportType())) auditDetails.put("coordinateAdjustedByAdmin", hasCoordinateOverride(request));
        auditLogService.recordReportDecision(adminId, reportId, AuditAction.REPORT_APPROVED, auditDetails);
        notificationService.createReportDecision(report, toilet.getName());
        return response(report, toilet.getName());
    }
    public ToiletReportResponse reject(Long adminId, Long reportId, ReviewToiletReportRequest request) {
        ToiletReport report = reportRepository.findByIdForUpdate(reportId).orElseThrow(() -> new IllegalArgumentException("제보를 찾을 수 없습니다."));
        Toilet toilet = toiletRepository.findById(report.getToiletId()).orElseThrow(() -> new IllegalArgumentException("대상 화장실을 찾을 수 없습니다."));
        report.reject(adminId, note(request));
        auditLogService.recordReportDecision(adminId, reportId, AuditAction.REPORT_REJECTED, Map.of("toiletId", report.getToiletId()));
        notificationService.createReportDecision(report, toilet.getName());
        return response(report, toilet.getName());
    }
    private void validateRequest(CreateToiletReportRequest request) {
        if (request == null || request.toiletId() == null || request.reportType() == null || request.reason() == null || request.reason().isBlank()) throw new IllegalArgumentException("화장실, 제보 유형, 제보 사유는 필수입니다.");
        if (request.reason().trim().length() > 500) throw new IllegalArgumentException("제보 사유는 500자 이하여야 합니다.");
        if ("COORDINATE_CORRECTION".equals(request.reportType())) {
            CoordinateAddressResolver.validateCoordinates(request.latitude(), request.longitude());
        } else if ("OPEN_TIME_CORRECTION".equals(request.reportType())) {
            if (request.openTime() == null || request.openTime().isBlank() || request.openTime().trim().length() > 50) throw new IllegalArgumentException("개방 시간은 50자 이하여야 합니다.");
        } else throw new IllegalArgumentException("지원하지 않는 제보 유형입니다.");
    }
    private String note(ReviewToiletReportRequest request) { return request == null || request.note() == null ? null : request.note().trim(); }
    private BigDecimal confirmedLatitude(ReviewToiletReportRequest request, ToiletReport report) { return request != null && request.confirmedLatitude() != null ? request.confirmedLatitude() : report.getProposedLatitude(); }
    private BigDecimal confirmedLongitude(ReviewToiletReportRequest request, ToiletReport report) { return request != null && request.confirmedLongitude() != null ? request.confirmedLongitude() : report.getProposedLongitude(); }
    private boolean hasCoordinateOverride(ReviewToiletReportRequest request) { return request != null && (request.confirmedLatitude() != null || request.confirmedLongitude() != null); }
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
