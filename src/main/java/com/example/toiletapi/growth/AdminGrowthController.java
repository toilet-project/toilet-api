package com.example.toiletapi.growth;

import com.example.toiletapi.auth.controller.AuthenticatedMutationBoundary;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Small explicit operations: enumerating accounts or viewing a plan never awards XP. */
@RestController
@RequestMapping("/api/admin/v1/growth")
public class AdminGrowthController {
    private final AdminGrowthAccess access;
    private final AdminGrowthOperations operations;
    private final GrowthService growth;
    public AdminGrowthController(AdminGrowthAccess access,AdminGrowthOperations operations,GrowthService growth) {
        this.access=access;this.operations=operations;this.growth=growth;
    }
    public record VersionRequest(String policyVersion) { }
    public record EligibilityRequest(Boolean excluded,String reason) { }

    @GetMapping("/policy/preview")
    public ResponseEntity<GrowthService.PolicyPreview> policy(@AuthenticationPrincipal Jwt jwt) {
        access.requireAdmin(jwt);return response(growth.previewPolicy());
    }
    @PostMapping("/policy/initialize")
    public ResponseEntity<GrowthService.PolicyPreview> initialize(@AuthenticationPrincipal Jwt jwt,
            @RequestBody VersionRequest body,HttpServletRequest request) {
        AuthenticatedMutationBoundary.requireTrustedOriginOrBearer(request,jwt);
        return response(operations.initialize(jwt,body.policyVersion()));
    }
    @GetMapping("/backfill/candidates")
    public ResponseEntity<AdminGrowthAccess.CandidatePage> candidates(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") long afterUserId,@RequestParam(defaultValue="50") int size) {
        access.requireAdmin(jwt);return response(access.candidates(afterUserId,size));
    }
    @GetMapping("/users/{userId}/preview")
    public ResponseEntity<GrowthService.Preview> preview(@AuthenticationPrincipal Jwt jwt,@PathVariable long userId) {
        access.requireAdmin(jwt);AdminGrowthOperations.requireId(userId);return response(growth.previewUser(userId));
    }
    @PostMapping("/users/{userId}/apply")
    public ResponseEntity<GrowthService.BackfillResult> apply(@AuthenticationPrincipal Jwt jwt,@PathVariable long userId,
            @RequestBody VersionRequest body,HttpServletRequest request) {
        AuthenticatedMutationBoundary.requireTrustedOriginOrBearer(request,jwt);
        return response(operations.apply(jwt,userId,body.policyVersion()));
    }
    @PostMapping("/reviews/{reviewId}/eligibility")
    public ResponseEntity<GrowthService.ExclusionResult> eligibility(@AuthenticationPrincipal Jwt jwt,@PathVariable long reviewId,
            @RequestBody EligibilityRequest body,HttpServletRequest request) {
        AuthenticatedMutationBoundary.requireTrustedOriginOrBearer(request,jwt);
        return response(operations.eligibility(jwt,reviewId,body.excluded(),body.reason()));
    }
    private static <T>ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
