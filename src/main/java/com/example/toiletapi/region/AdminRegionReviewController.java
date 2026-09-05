package com.example.toiletapi.region;

import com.example.toiletapi.quality.dto.DuplicateCoordinateToiletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import static com.example.toiletapi.region.RegionReviewModels.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/v1/regions")
public class AdminRegionReviewController {
    private final RegionReviewService service;
    @GetMapping
    public Page<Item> search(@RequestParam(defaultValue="REVIEW") Filter status,
                             @RequestParam(defaultValue="") String keyword,
                             @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return service.search(status, keyword, page, size);
    }
    @GetMapping("/{id}")
    public Detail detail(@PathVariable long id) { return service.detail(id); }
    @GetMapping("/{id}/history")
    public Page<History> history(@PathVariable long id, @RequestParam(defaultValue="0") int page,
                                 @RequestParam(defaultValue="10") int size) { return service.history(id, page, size); }
    @PostMapping("/{id}/coordinates")
    public DuplicateCoordinateToiletResponse correct(@PathVariable long id, @Valid @RequestBody Correction request,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return service.correct(Long.parseLong(jwt.getSubject()), id, request);
    }
}
