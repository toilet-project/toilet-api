package com.example.toiletapi.policy.repository;

import com.example.toiletapi.policy.model.UserPolicyConsent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPolicyConsentRepository extends JpaRepository<UserPolicyConsent, Long> {
    List<UserPolicyConsent> findAllByUserIdAndWithdrawnAtIsNull(Long userId);
    Optional<UserPolicyConsent> findByUserIdAndPolicyDocumentId(Long userId, Long policyDocumentId);
}
