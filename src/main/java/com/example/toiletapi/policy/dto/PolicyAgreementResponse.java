package com.example.toiletapi.policy.dto;

import com.example.toiletapi.policy.model.PolicyKey;
import com.example.toiletapi.policy.model.UserPolicyConsent;
import java.time.LocalDateTime;

public record PolicyAgreementResponse(
        PolicyKey key,
        String version,
        String title,
        String contentPath,
        LocalDateTime agreedAt
) {
    public static PolicyAgreementResponse from(UserPolicyConsent consent) {
        var policy = consent.getPolicyDocument();
        return new PolicyAgreementResponse(policy.getPolicyKey(), policy.getVersion(), policy.getTitle(),
                policy.getContentPath(), consent.getAgreedAt());
    }
}
