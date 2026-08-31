package com.example.toiletapi.report.controller;
import com.example.toiletapi.report.dto.*; import com.example.toiletapi.report.service.ToiletReportService;
import com.example.toiletapi.report.model.ReportStatus; import java.time.LocalDate; import java.util.List; import lombok.RequiredArgsConstructor; import org.springframework.format.annotation.DateTimeFormat; import org.springframework.http.HttpStatus; import org.springframework.security.core.annotation.AuthenticationPrincipal; import org.springframework.security.oauth2.jwt.Jwt; import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor
public class ToiletReportController {
    private final ToiletReportService service;
    @PostMapping("/api/v1/reports") @ResponseStatus(HttpStatus.CREATED) public ToiletReportResponse submit(@RequestBody CreateToiletReportRequest request, @AuthenticationPrincipal Jwt jwt) { return service.submit(userId(jwt), request); }
    @GetMapping("/api/v1/reports/me") public List<ToiletReportResponse> mine(@AuthenticationPrincipal Jwt jwt) { return service.mine(userId(jwt)); }
    @GetMapping("/api/admin/v1/reports/summary") public ToiletReportDashboardResponse pendingSummary() { return service.pendingDashboard(); }
    @GetMapping("/api/admin/v1/reports/search") public ToiletReportPageResponse searchPage(@RequestParam(required = false) ReportStatus status,
                                                                                              @RequestParam(defaultValue = "") String keyword,
                                                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                                                              @RequestParam(defaultValue = "OLDEST") String sort,
                                                                                              @RequestParam(defaultValue = "0") int page,
                                                                                              @RequestParam(defaultValue = "20") int size) { return service.searchPage(status, keyword, from, to, sort, page, size); }
    @GetMapping("/api/admin/v1/reports/{reportId}") public ToiletReportDetailResponse pendingDetail(@PathVariable Long reportId) { return service.pendingDetail(reportId); }
    /** @deprecated 관리자 화면 전환 기간의 하위 호환용 전체 목록이다. */
    @Deprecated
    @GetMapping("/api/admin/v1/reports") public List<ToiletReportResponse> pending() { return service.pending(); }
    @PostMapping("/api/admin/v1/reports/{reportId}/approve") public ToiletReportResponse approve(@PathVariable Long reportId, @RequestBody(required = false) ReviewToiletReportRequest request, @AuthenticationPrincipal Jwt jwt) { return service.approve(userId(jwt), reportId, request); }
    @PostMapping("/api/admin/v1/reports/{reportId}/reject") public ToiletReportResponse reject(@PathVariable Long reportId, @RequestBody(required = false) ReviewToiletReportRequest request, @AuthenticationPrincipal Jwt jwt) { return service.reject(userId(jwt), reportId, request); }
    private Long userId(Jwt jwt) { try { return Long.valueOf(jwt.getSubject()); } catch (Exception exception) { throw new IllegalArgumentException("유효하지 않은 사용자 토큰입니다."); } }
}
