package com.example.toiletapi.review;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.example.toiletapi.auth.controller.AuthenticatedMutationBoundary;
import jakarta.servlet.http.HttpServletRequest;

/** Monitoring and explicitly confirmed policy moderation; no author-recovery operation. */
@RestController
@RequestMapping("/api/admin/v1/reviews")
public class AdminReviewController {
    private final AdminReviewAccess access;
    private final AdminReviewService service;
    private final AdminReviewModerationService moderation;
    public AdminReviewController(AdminReviewAccess access,AdminReviewService service,AdminReviewModerationService moderation) { this.access=access;this.service=service;this.moderation=moderation; }
    @GetMapping
    public AdminReviewModels.Page page(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String cursor,
                                       @RequestParam(defaultValue="10") int size) {
        access.requireAdmin(jwt);return service.page(cursor,size);
    }
    @GetMapping("/{id}")
    public AdminReviewModels.Detail detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) {
        access.requireAdmin(jwt);return service.detail(id);
    }
    @DeleteMapping("/{id}")
    public AdminReviewModels.Removed remove(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,
                                            @RequestBody AdminReviewModels.Remove request,HttpServletRequest http) {
        AuthenticatedMutationBoundary.requireTrustedOriginOrBearer(http,jwt);
        return moderation.remove(jwt,id,request);
    }
}
