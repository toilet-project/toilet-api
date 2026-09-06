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
    public String updateNickname(Long userId, String input) {
        String nickname = input == null ? "" : java.text.Normalizer.normalize(input.strip(), java.text.Normalizer.Form.NFC);
        if (nickname.length() < 2 || nickname.length() > 30
                || nickname.codePoints().allMatch(code -> Character.isWhitespace(code) || Character.isSpaceChar(code))
                || nickname.codePoints().anyMatch(code ->
                Character.isISOControl(code) || Character.getType(code) == Character.FORMAT)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "닉네임은 제어 문자를 제외한 2~30자로 입력해 주세요.");
        }
        AppUser user = userRepository.findById(userId).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));
        if (user.getStatus() != com.example.toiletapi.auth.model.UserStatus.ACTIVE) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        }
        user.changeDisplayName(nickname);
        return nickname;
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
