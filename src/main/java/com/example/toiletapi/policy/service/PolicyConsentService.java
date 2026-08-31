package com.example.toiletapi.policy.service;

import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.model.UserStatus;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.policy.dto.*;
import com.example.toiletapi.policy.model.*;
import com.example.toiletapi.policy.repository.PolicyDocumentRepository;
import com.example.toiletapi.policy.repository.UserPolicyConsentRepository;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyConsentService {
    private final PolicyDocumentRepository policyRepository;
    private final UserPolicyConsentRepository consentRepository;
    private final AppUserRepository userRepository;

    public PolicyConsentService(PolicyDocumentRepository policyRepository,
                                UserPolicyConsentRepository consentRepository,
                                AppUserRepository userRepository) {
        this.policyRepository = policyRepository;
        this.consentRepository = consentRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<PolicyDocumentResponse> activePolicies() {
        return policyRepository.findAllByActiveTrueOrderByRequiredDescPolicyKeyAsc().stream()
                .map(PolicyDocumentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public PolicyConsentStatusResponse status(Long userId) {
        List<UserPolicyConsent> activeConsents = consentRepository.findAllByUserIdAndWithdrawnAtIsNull(userId);
        Set<Long> agreedPolicyIds = activeConsents.stream()
                .map(consent -> consent.getPolicyDocument().getId()).collect(Collectors.toSet());
        List<PolicyDocumentResponse> missing = policyRepository
                .findAllByActiveTrueAndRequiredTrueOrderByPolicyKeyAsc().stream()
                .filter(policy -> !agreedPolicyIds.contains(policy.getId()))
                .map(PolicyDocumentResponse::from).toList();
        List<PolicyAgreementResponse> agreed = activeConsents.stream()
                .map(PolicyAgreementResponse::from)
                .sorted(Comparator.comparing(agreement -> agreement.key().name()))
                .toList();
        return new PolicyConsentStatusResponse(!missing.isEmpty(), missing, agreed);
    }

    @Transactional
    public PolicyConsentStatusResponse agree(Long userId, Set<PolicyKey> policyKeys) {
        AppUser user = activeUser(userId);
        List<PolicyDocument> required = policyRepository.findAllByActiveTrueAndRequiredTrueOrderByPolicyKeyAsc();
        Set<PolicyKey> requiredKeys = required.stream().map(PolicyDocument::getPolicyKey).collect(Collectors.toSet());
        if (!policyKeys.containsAll(requiredKeys)) {
            throw new IllegalArgumentException("모든 필수 항목에 동의해야 합니다.");
        }
        for (PolicyDocument policy : required) {
            UserPolicyConsent consent = consentRepository.findByUserIdAndPolicyDocumentId(userId, policy.getId())
                    .orElseGet(() -> UserPolicyConsent.agree(user, policy, ConsentSource.WEB_OAUTH_ONBOARDING));
            if (consent.getId() != null) consent.restore(ConsentSource.WEB_OAUTH_ONBOARDING);
            consentRepository.save(consent);
        }
        user.activateAfterConsent();
        return status(userId);
    }

    @Transactional(readOnly = true)
    public void requireEligibleUser(Long userId) {
        activeUser(userId);
        if (status(userId).consentRequired()) throw new PolicyConsentRequiredException();
    }

    private AppUser activeUser(Long userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.WITHDRAWN) {
            throw new IllegalStateException("이용할 수 없는 계정입니다.");
        }
        return user;
    }
}
