package com.example.toiletapi.policy.controller;

import com.example.toiletapi.policy.dto.*;
import com.example.toiletapi.policy.service.PolicyConsentService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class PolicyController {
    private final PolicyConsentService service;

    @GetMapping("/api/v1/policies")
    public List<PolicyDocumentResponse> policies() {
        return service.activePolicies();
    }

    @GetMapping("/api/v1/auth/consents/status")
    public PolicyConsentStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
        return service.status(userId(jwt));
    }

    @PostMapping("/api/v1/auth/consents")
    public PolicyConsentStatusResponse agree(@Valid @RequestBody AgreePoliciesRequest request,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.agree(userId(jwt), request.policyKeys());
    }

    private Long userId(Jwt jwt) {
        try { return Long.valueOf(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("인증 사용자 식별자가 올바르지 않습니다."); }
    }
}
