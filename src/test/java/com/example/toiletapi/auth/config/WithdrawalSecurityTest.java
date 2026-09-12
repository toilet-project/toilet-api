package com.example.toiletapi.auth.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.toiletapi.auth.model.*;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.auth.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jwt.BadJwtException;

class WithdrawalSecurityTest {
    @Test void oldAccessTokenStaysRevokedEvenAfterRecoveryAndAccountDeletion() {
        var repo = mock(AppUserRepository.class);
        var user = AppUser.create("회원", null, false); user.activateAfterConsent();
        when(repo.lockById(1L)).thenReturn(Optional.of(user)); when(repo.findById(1L)).thenReturn(Optional.of(user));
        var config = new JwtConfig();
        var props = new AuthTokenProperties(Base64.getEncoder().encodeToString(new byte[32]), Duration.ofMinutes(15), Duration.ofDays(14));
        var key = config.jwtSecretKey(props);
        var tokens = new AuthTokenService(config.jwtEncoder(key), mock(RefreshTokenStore.class), props, repo);
        var decoder = config.accountAwareJwtDecoder(key, repo);
        String original = tokens.issue(1L, List.of(Role.USER)).accessToken();
        assertThat(decoder.decode(original).getSubject()).isEqualTo("1");
        user.withdraw();
        assertThatThrownBy(() -> decoder.decode(original)).isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> tokens.issue(1L, List.of(Role.USER))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        user.restore("회원");
        assertThatThrownBy(() -> decoder.decode(original)).isInstanceOf(BadJwtException.class);
        String restored = tokens.issue(1L, List.of(Role.USER)).accessToken();
        assertThat(decoder.decode(restored).getClaimAsStringList("roles")).containsExactly("USER");
        when(repo.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> decoder.decode(restored)).isInstanceOf(BadJwtException.class);
    }

    @Test void recoveryOAuthHasNoAccessTokensNoSessionAndNoProofInUrl() throws Exception {
        var login = mock(OAuthLoginService.class); var tokens = mock(AuthTokenService.class);
        var store = mock(RecoveryChallengeStore.class);
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("sub", "social-id"), "sub");
        when(login.login("google", principal)).thenReturn(new OAuthLoginService.LoginUser(7L, List.of(), false, "withdrawal-key"));
        when(store.issue(7L, "withdrawal-key")).thenReturn("opaque-proof");
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(OAuthReturnTargets.SESSION_ATTRIBUTE, OAuthReturnTargets.PREVIEW);
        var response = new MockHttpServletResponse();
        var auth = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
        SecurityContextHolder.getContext().setAuthentication(auth);
        new OAuthLoginSuccessHandler(login, tokens, "https://geupddong.com", store, mock(com.example.toiletapi.photo.PhotoSync.class)).onAuthenticationSuccess(request, response, auth);
        verifyNoInteractions(tokens);
        assertThat(request.getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("https://preview.geupddong.com/?recovery=required");
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> {
            assertThat(cookie).contains("geupddong_recovery=opaque-proof", "HttpOnly", "Secure", "Max-Age=600", "Path=/api/v1/auth/recovery");
        });
        assertThat(response.getHeaders("Set-Cookie").stream().filter(c -> c.contains("Max-Age=0")).count()).isEqualTo(2);
    }
}
