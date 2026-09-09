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
    private final com.example.toiletapi.auth.repository.AccountWithdrawalRepository withdrawals;
    private final AccountLifecycleGate lifecycle;
    private final AccountMaintenanceTransactions maintenance;

    public AccountService(AppUserRepository userRepository, UserSocialAccountRepository socialAccountRepository,
                          UserRoleAssignmentRepository roleRepository, UserPolicyConsentRepository consentRepository,
                          RefreshTokenStore refreshTokenStore, AuditLogService auditLogService,
                          com.example.toiletapi.auth.repository.AccountWithdrawalRepository withdrawals, AccountLifecycleGate lifecycle,
                          AccountMaintenanceTransactions maintenance) {
        this.userRepository = userRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.roleRepository = roleRepository;
        this.consentRepository = consentRepository;
        this.refreshTokenStore = refreshTokenStore;
        this.auditLogService = auditLogService;
        this.withdrawals = withdrawals;
        this.lifecycle = lifecycle;
        this.maintenance = maintenance;
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
        AppUser user = userRepository.lockById(userId).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));
        if (user.getStatus() != com.example.toiletapi.auth.model.UserStatus.ACTIVE) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        }
        user.changeDisplayName(nickname);
        return nickname;
    }

    public WithdrawalReceipt withdraw(Long userId, boolean retainForRecovery, String consentVersion) {
        lifecycle.requireWithdrawal();
        return maintenance.execute(()->withdrawLocked(userId,retainForRecovery,consentVersion));
    }
    private WithdrawalReceipt withdrawLocked(Long userId, boolean retainForRecovery, String consentVersion) {
        if (retainForRecovery && !com.example.toiletapi.auth.model.AccountWithdrawal.CONSENT_VERSION.equals(consentVersion)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "복구용 정보 보관 동의를 다시 확인해 주세요.");
        }
        AppUser user = userRepository.lockById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if (user.getStatus() == com.example.toiletapi.auth.model.UserStatus.WITHDRAWN) {
            var previous = withdrawals.findById(userId).orElseThrow(() -> new IllegalStateException("탈퇴 상태 확인이 필요합니다."));
            return new WithdrawalReceipt(previous.getPurgeAfter().atOffset(java.time.ZoneOffset.ofHours(9)));
        }
        if (retainForRecovery && user.getStatus() == com.example.toiletapi.auth.model.UserStatus.SUSPENDED) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "이용 제한 계정은 복구용 보관을 선택할 수 없습니다.");
        }
        var withdrawal = withdrawals.save(com.example.toiletapi.auth.model.AccountWithdrawal.create(user, retainForRecovery,
                com.example.toiletapi.global.time.KoreanTime.now()));
        consentRepository.findAllByUserIdAndWithdrawnAtIsNull(userId).forEach(consent -> consent.withdraw());
        if (retainForRecovery) socialAccountRepository.findAllByUserId(userId).forEach(account -> account.clearPersonalProfile());
        else socialAccountRepository.deleteAllByUserId(userId);
        roleRepository.deleteAllByUserId(userId);
        user.withdraw();
        auditLogService.record(userId, com.example.toiletapi.auth.model.AuditAction.USER_WITHDRAWN,
                "USER", userId, Map.of("reason", "SELF_SERVICE"));
        refreshTokenStore.deleteAllForUser(userId);
        return new WithdrawalReceipt(withdrawal.getPurgeAfter().atOffset(java.time.ZoneOffset.ofHours(9)));
    }
    public record WithdrawalReceipt(java.time.OffsetDateTime purgeAfter) { }
}
