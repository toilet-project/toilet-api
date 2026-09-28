package com.example.toiletapi.auth.privacy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.example.toiletapi.auth.model.*;
import com.example.toiletapi.auth.repository.*;
import com.example.toiletapi.auth.service.*;
import com.example.toiletapi.policy.service.PolicyConsentService;
import com.example.toiletapi.policy.dto.PolicyConsentStatusResponse;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

class OAuthEmailPrivacyTest {
    @Test void newAndExistingSocialLoginsNeverPersistPlaintextAndKeepTheSameUserId() {
        var protection = new EmailProtection(EmailProtectionTest.settings());
        var users = mock(AppUserRepository.class);
        var socials = mock(UserSocialAccountRepository.class);
        var roles = mock(UserRolePolicyService.class);
        var consent = mock(PolicyConsentService.class);
        var service = new OAuthLoginService(users,socials,roles,consent,mock(AccountWithdrawalRepository.class),
                mock(AccountErasureService.class),new AccountLifecycleGate(false,true,true),protection);
        ReflectionTestUtils.setField(service,"entityManager",mock(jakarta.persistence.EntityManager.class));
        var principal = mock(OAuth2User.class);
        when(principal.getAttributes()).thenReturn(Map.of("sub","social-identity","name","Member",
                "email","member@example.test","email_verified",true));
        var savedUser = new AppUser[1];
        var savedSocial = new UserSocialAccount[1];
        when(socials.findByProviderAndProviderSubjectHash(any(),any())).thenAnswer(call -> Optional.ofNullable(savedSocial[0]));
        when(users.save(any())).thenAnswer(call -> {
            AppUser user = call.getArgument(0);
            assertThat(user.getEmail()).isNull();
            assertThat(user.getEmailCiphertext()).isNotBlank();
            ReflectionTestUtils.setField(user,"id",7L); savedUser[0]=user; return user;
        });
        when(socials.save(any())).thenAnswer(call -> {
            UserSocialAccount social=call.getArgument(0);
            assertThat(social.getProviderEmail()).isNull();
            assertThat(social.getProviderEmailCiphertext()).isNotBlank();
            savedSocial[0]=social; return social;
        });
        when(users.lockById(7L)).thenAnswer(call -> Optional.of(savedUser[0]));
        when(roles.ensureInitialRoles(any())).thenReturn(Set.of(Role.USER));
        when(consent.status(7L)).thenReturn(new PolicyConsentStatusResponse(false,List.of(),List.of()));
        assertThat(service.login("google",principal).userId()).isEqualTo(7L);
        assertThat(service.login("google",principal).userId()).isEqualTo(7L);
        verify(users,times(1)).save(any()); verify(socials,times(1)).save(any());
        assertThat(savedUser[0].getEmail()).isNull();
        assertThat(protection.email(savedUser[0])).isEqualTo("member@example.test");
    }
}
