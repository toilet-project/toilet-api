package com.example.toiletapi.policy.dto;

import com.example.toiletapi.policy.model.PolicyKey;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

public record AgreePoliciesRequest(@NotEmpty Set<PolicyKey> policyKeys) { }
