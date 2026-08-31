package com.example.toiletapi.quality.controller;

import com.example.toiletapi.quality.dto.CorrectToiletCoordinateRequest;
import com.example.toiletapi.quality.dto.DuplicateCoordinateGroupDetailResponse;
import com.example.toiletapi.quality.dto.DuplicateCoordinateGroupPageResponse;
import com.example.toiletapi.quality.dto.DuplicateCoordinateGroupResponse;
import com.example.toiletapi.quality.dto.DuplicateCoordinateToiletResponse;
import com.example.toiletapi.quality.dto.ReviewCoordinateGroupRequest;
import com.example.toiletapi.quality.model.CoordinateQualityStatus;
import com.example.toiletapi.quality.service.CoordinateQualityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/v1/data-quality")
public class AdminCoordinateQualityController {
    private final CoordinateQualityService service;

    @GetMapping("/duplicate-coordinates")
    public DuplicateCoordinateGroupPageResponse search(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) CoordinateQualityStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.search(keyword, status, page, size);
    }

    @GetMapping("/duplicate-coordinates/{groupKey}")
    public DuplicateCoordinateGroupDetailResponse detail(@PathVariable String groupKey) {
        return service.detail(groupKey);
    }

    @PatchMapping("/duplicate-coordinates/{groupKey}/review")
    public DuplicateCoordinateGroupResponse review(@PathVariable String groupKey,
                                                    @Valid @RequestBody ReviewCoordinateGroupRequest request,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return service.reviewGroup(userId(jwt), groupKey, request);
    }

    @PostMapping("/toilets/{toiletId}/coordinates")
    public DuplicateCoordinateToiletResponse correct(@PathVariable Long toiletId,
                                                      @Valid @RequestBody CorrectToiletCoordinateRequest request,
                                                      @AuthenticationPrincipal Jwt jwt) {
        return service.correctToilet(userId(jwt), toiletId, request);
    }

    private Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
