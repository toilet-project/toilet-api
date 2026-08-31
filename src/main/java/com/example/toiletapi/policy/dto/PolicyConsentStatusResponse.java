package com.example.toiletapi.policy.dto;

import java.util.List;

public record PolicyConsentStatusResponse(
        boolean consentRequired,
        List<PolicyDocumentResponse> missingPolicies,
        List<PolicyAgreementResponse> agreedPolicies
) { }
