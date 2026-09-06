package com.example.toiletapi.auth.service;

import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.model.Role;
import com.example.toiletapi.auth.model.SocialProvider;
import com.example.toiletapi.auth.model.UserSocialAccount;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.auth.repository.UserSocialAccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.toiletapi.policy.service.PolicyConsentService;

@Service
public class OAuthLoginService {
    private final AppUserRepository userRepository;
    private final UserSocialAccountRepository socialAccountRepository;
    private final UserRolePolicyService rolePolicyService;
    private final PolicyConsentService policyConsentService;
    private final com.example.toiletapi.auth.repository.AccountWithdrawalRepository withdrawals;
    private final AccountErasureService erasure;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    public OAuthLoginService(AppUserRepository userRepository, UserSocialAccountRepository socialAccountRepository,
                             UserRolePolicyService rolePolicyService, PolicyConsentService policyConsentService,
                             com.example.toiletapi.auth.repository.AccountWithdrawalRepository withdrawals,
                             AccountErasureService erasure) {
        this.userRepository = userRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.rolePolicyService = rolePolicyService;
        this.policyConsentService = policyConsentService;
        this.withdrawals = withdrawals; this.erasure = erasure;
    }

    @Transactional
    public LoginUser login(String registrationId, OAuth2User oauthUser) {
        Profile profile = Profile.from(registrationId, oauthUser.getAttributes());
        UserSocialAccount socialAccount = socialAccountRepository
                .findByProviderAndProviderSubjectHash(profile.provider(), sha256(profile.subject()))
                .orElseGet(() -> link(profile));
        // Serialize OAuth against erasure/withdrawal; no profile writes or roles before this check.
        var user = userRepository.lockById(socialAccount.getUser().getId())
                .orElseThrow(() -> new IllegalStateException("계정 상태가 변경되었습니다. 다시 로그인해 주세요."));
        entityManager.refresh(user); // Do not use a pre-lock OAuth association snapshot after concurrent withdrawal.
        if (user.getStatus() == com.example.toiletapi.auth.model.UserStatus.WITHDRAWN) {
            var withdrawal = withdrawals.findById(user.getId()).orElse(null);
            var now = com.example.toiletapi.global.time.KoreanTime.now();
            if (withdrawal != null && withdrawal.canRecover(now)) {
                return new LoginUser(user.getId(), List.of(), false, withdrawal.getWithdrawalKey());
            }
            if (!erasure.eraseIfDue(user.getId(), now)) throw new IllegalStateException("탈퇴 계정 확인이 필요합니다.");
            socialAccount = link(profile);
        } else if (user.getStatus() == com.example.toiletapi.auth.model.UserStatus.SUSPENDED) {
            throw new IllegalStateException("이용이 제한된 계정입니다.");
        }
        socialAccount.getUser().refreshOAuthProfile(
                profile.displayName(), profile.email(), profile.emailVerified());
        socialAccount.recordLogin(profile.email());
        List<Role> roles = List.copyOf(rolePolicyService.ensureInitialRoles(socialAccount.getUser()));
        return new LoginUser(socialAccount.getUser().getId(), roles,
                policyConsentService.status(socialAccount.getUser().getId()).consentRequired());
    }

    private UserSocialAccount link(Profile profile) {
        AppUser user = userRepository.save(AppUser.create(profile.displayName(), profile.email(), profile.emailVerified()));
        return socialAccountRepository.save(UserSocialAccount.link(user, profile.provider(), sha256(profile.subject()), profile.email()));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("소셜 계정 식별자를 처리할 수 없습니다.", exception);
        }
    }

    record Profile(SocialProvider provider, String subject, String displayName, String email, boolean emailVerified) {
        @SuppressWarnings("unchecked")
        static Profile from(String registrationId, Map<String, Object> attributes) {
            if ("google".equals(registrationId)) {
                return new Profile(SocialProvider.GOOGLE, String.valueOf(attributes.get("sub")),
                        String.valueOf(attributes.getOrDefault("name", "Google 사용자")), (String) attributes.get("email"),
                        Boolean.TRUE.equals(attributes.get("email_verified")));
            }
            if ("kakao".equals(registrationId)) {
                Map<String, Object> account = (Map<String, Object>) attributes.getOrDefault("kakao_account", Map.of());
                Map<String, Object> profile = (Map<String, Object>) account.getOrDefault("profile", Map.of());
                return new Profile(SocialProvider.KAKAO, String.valueOf(attributes.get("id")),
                        String.valueOf(profile.getOrDefault("nickname", "카카오 사용자")), (String) account.get("email"),
                        Boolean.TRUE.equals(account.get("is_email_verified")));
            }
            throw new IllegalArgumentException("지원하지 않는 소셜 로그인 제공자입니다.");
        }
    }

    public record LoginUser(Long userId, List<Role> roles, boolean consentRequired, String recoveryKey) {
        public LoginUser(Long userId, List<Role> roles, boolean consentRequired) { this(userId, roles, consentRequired, null); }
    }
}
