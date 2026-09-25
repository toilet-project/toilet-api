package com.example.toiletapi.auth.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.model.Role;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.auth.service.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

class MobileAuthControllerTest {
    private final MobileLoginCodeStore codes = mock(MobileLoginCodeStore.class);
    private final RefreshTokenStore refresh = mock(RefreshTokenStore.class);
    private final AuthTokenService tokens = mock(AuthTokenService.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final UserRolePolicyService roles = mock(UserRolePolicyService.class);
    private final MobileAuthController controller = new MobileAuthController(true, codes, refresh, tokens, users, roles);

    @Test void refreshConsumesOldTokenAndReturnsUncachedSession() {
        String token = "r".repeat(64);
        when(refresh.consume(token)).thenReturn(Optional.of(7L));
        when(users.findById(7L)).thenReturn(Optional.of(AppUser.create("앱 이용자", "app@example.test", true)));
        when(roles.rolesOf(7L)).thenReturn(Set.of(Role.USER));
        when(tokens.issue(7L, List.of(Role.USER))).thenReturn(new AuthTokenService.IssuedTokens("access", "n".repeat(64), Instant.now().plusSeconds(60), Duration.ofDays(14)));
        var response = controller.refresh(new MobileAuthController.RefreshRequest(token));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("n".repeat(64), response.getBody().refreshToken());
        verify(refresh).consume(token); verify(refresh, never()).findUserId(any());
    }

    @Test void reusedOrUnknownRefreshCannotIssueTokens() {
        when(refresh.consume(any())).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> controller.refresh(new MobileAuthController.RefreshRequest("r".repeat(64))));
        verifyNoInteractions(tokens);
    }

    @Test void missingPkceAndUnknownProviderCannotStartLogin() {
        assertThrows(ResponseStatusException.class, () -> controller.login("other", "s".repeat(43), "c".repeat(43), new MockHttpServletRequest(), new MockHttpServletResponse()));
        assertThrows(ResponseStatusException.class, () -> controller.login("google", "state", "plain", new MockHttpServletRequest(), new MockHttpServletResponse()));
    }

    @Test void rolloutIsDisabledByDefaultAndLogoutRevokesOnlyPresentedToken() {
        var disabled = new MobileAuthController(false, codes, refresh, tokens, users, roles);
        assertThrows(ResponseStatusException.class, () -> disabled.exchange(new MobileAuthController.ExchangeRequest("x", "x")));
        controller.logout(new MobileAuthController.RefreshRequest("r".repeat(64)));
        verify(tokens).revoke("r".repeat(64));
    }
}
