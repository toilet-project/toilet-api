package com.example.toiletapi.policy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.model.UserStatus;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.policy.model.PolicyDocument;
import com.example.toiletapi.policy.model.PolicyKey;
import com.example.toiletapi.policy.model.UserPolicyConsent;
import com.example.toiletapi.policy.repository.PolicyDocumentRepository;
import com.example.toiletapi.policy.repository.UserPolicyConsentRepository;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PolicyConsentServiceTest {
    private final PolicyDocumentRepository policyRepository = mock(PolicyDocumentRepository.class);
    private final UserPolicyConsentRepository consentRepository = mock(UserPolicyConsentRepository.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final PolicyConsentService service = new PolicyConsentService(
            policyRepository, consentRepository, userRepository);

    @Test
    void reportsMissingRequiredPolicies() {
        PolicyDocument terms = policy(1L, PolicyKey.SERVICE_TERMS);
        when(policyRepository.findAllByActiveTrueAndRequiredTrueOrderByPolicyKeyAsc()).thenReturn(List.of(terms));
        when(consentRepository.findAllByUserIdAndWithdrawnAtIsNull(10L)).thenReturn(List.of());

        var result = service.status(10L);

        assertThat(result.consentRequired()).isTrue();
        assertThat(result.missingPolicies()).extracting(policy -> policy.key())
                .containsExactly(PolicyKey.SERVICE_TERMS);
    }

    @Test
    void rejectsIncompleteRequiredConsent() throws Exception {
        AppUser user = persistedUser();
        PolicyDocument terms = policy(1L, PolicyKey.SERVICE_TERMS);
        PolicyDocument privacy = policy(2L, PolicyKey.PRIVACY_COLLECTION);
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(policyRepository.findAllByActiveTrueAndRequiredTrueOrderByPolicyKeyAsc())
                .thenReturn(List.of(terms, privacy));

        assertThatThrownBy(() -> service.agree(10L, Set.of(PolicyKey.SERVICE_TERMS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("모든 필수 항목");
    }

    @Test
    void storesEveryRequiredVersionAndActivatesAccount() throws Exception {
        AppUser user = persistedUser();
        List<PolicyDocument> required = List.of(
                policy(1L, PolicyKey.SERVICE_TERMS),
                policy(2L, PolicyKey.PRIVACY_COLLECTION),
                policy(3L, PolicyKey.AGE_14_PLUS));
        List<UserPolicyConsent> stored = new ArrayList<>();
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(policyRepository.findAllByActiveTrueAndRequiredTrueOrderByPolicyKeyAsc()).thenReturn(required);
        when(consentRepository.findAllByUserIdAndWithdrawnAtIsNull(10L)).thenAnswer(ignored -> List.copyOf(stored));
        when(consentRepository.findByUserIdAndPolicyDocumentId(Mockito.eq(10L), any())).thenReturn(Optional.empty());
        when(consentRepository.save(any())).thenAnswer(invocation -> {
            UserPolicyConsent consent = invocation.getArgument(0);
            stored.add(consent);
            return consent;
        });

        var result = service.agree(10L, Set.of(
                PolicyKey.SERVICE_TERMS, PolicyKey.PRIVACY_COLLECTION, PolicyKey.AGE_14_PLUS));

        assertThat(stored).hasSize(3);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.consentRequired()).isFalse();
    }

    private static PolicyDocument policy(Long id, PolicyKey key) {
        PolicyDocument policy = mock(PolicyDocument.class);
        when(policy.getId()).thenReturn(id);
        when(policy.getPolicyKey()).thenReturn(key);
        when(policy.getVersion()).thenReturn("1.0");
        when(policy.getTitle()).thenReturn(key.name());
        when(policy.isRequired()).thenReturn(true);
        when(policy.getContentPath()).thenReturn("/policies/test");
        return policy;
    }

    private static AppUser persistedUser() throws Exception {
        AppUser user = AppUser.create("사용자", "user@example.com", true);
        Field id = AppUser.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(user, 10L);
        return user;
    }
}
