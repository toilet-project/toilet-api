package com.example.toiletapi.report.controller;

import com.example.toiletapi.report.service.ReportResolutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/admin/v1/reports/{reportId}/actions")
public class ReportResolutionController {
    private final ReportResolutionService service;
    @GetMapping public ReportResolutionService.State read(@PathVariable long reportId) { return service.read(reportId); }
    @PostMapping public ReportResolutionService.State apply(@PathVariable long reportId, @RequestBody ReportResolutionService.Request request, @AuthenticationPrincipal Jwt jwt) {
        return service.apply(Long.parseLong(jwt.getSubject()), reportId, request);
    }
    @ExceptionHandler(ReportResolutionService.Conflict.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> conflict(ReportResolutionService.Conflict error) { return Map.of("error", Map.of("code", "FACILITY_CHANGED", "message", error.getMessage())); }
}
