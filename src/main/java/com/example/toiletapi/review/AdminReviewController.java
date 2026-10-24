package com.example.toiletapi.review;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Monitoring only: no edit, hide, delete, export or author-recovery operation. */
@RestController
@RequestMapping("/api/admin/v1/reviews")
public class AdminReviewController {
    private final AdminReviewAccess access;
    private final AdminReviewService service;
    public AdminReviewController(AdminReviewAccess access,AdminReviewService service) { this.access=access;this.service=service; }
    @GetMapping
    public AdminReviewModels.Page page(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String cursor,
                                       @RequestParam(defaultValue="20") int size) {
        access.requireAdmin(jwt);return service.page(cursor,size);
    }
    @GetMapping("/{id}")
    public AdminReviewModels.Detail detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) {
        access.requireAdmin(jwt);return service.detail(id);
    }
}
