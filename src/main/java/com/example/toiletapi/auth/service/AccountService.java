package com.example.toiletapi.auth.service;

import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.auth.repository.UserRoleAssignmentRepository;
import com.example.toiletapi.auth.repository.UserSocialAccountRepository;
import com.example.toiletapi.policy.repository.UserPolicyConsentRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final AppUserRepository userRepository;
    private final UserSocialAccountRepository socialAccountRepository;
    private final UserRoleAssignmentRepository roleRepository;
    private final UserPolicyConsentRepository consentRepository;
    private final RefreshTokenStore refreshTokenStore;
    private final AuditLogService auditLogService;

    public AccountService(AppUserRepository userRepository, UserSocialAccountRepository socialAccountRepository,
                          UserRoleAssignmentRepository roleRepository, UserPolicyConsentRepository consentRepository,
                          RefreshTokenStore refreshTokenStore, AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.roleRepository = roleRepository;
        this.consentRepository = consentRepository;
        this.refreshTokenStore = refreshTokenStore;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void withdraw(Long userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        consentRepository.findAllByUserIdAndWithdrawnAtIsNull(userId).forEach(consent -> consent.withdraw());
        socialAccountRepository.deleteAllByUserId(userId);
        roleRepository.deleteAllByUserId(userId);
        user.withdraw();
        auditLogService.record(userId, com.example.toiletapi.auth.model.AuditAction.USER_WITHDRAWN,
                "USER", userId, Map.of("reason", "SELF_SERVICE"));
        refreshTokenStore.deleteAllForUser(userId);
    }
}
