package com.example.toiletapi.policy.dto;

import com.example.toiletapi.policy.model.PolicyDocument;
import com.example.toiletapi.policy.model.PolicyKey;
import java.time.LocalDate;

public record PolicyDocumentResponse(
        Long id,
        PolicyKey key,
        String version,
        String title,
        boolean required,
        LocalDate effectiveAt,
        String contentPath
) {
    public static PolicyDocumentResponse from(PolicyDocument policy) {
        return new PolicyDocumentResponse(policy.getId(), policy.getPolicyKey(), policy.getVersion(),
                policy.getTitle(), policy.isRequired(), policy.getEffectiveAt(), policy.getContentPath());
    }
}
