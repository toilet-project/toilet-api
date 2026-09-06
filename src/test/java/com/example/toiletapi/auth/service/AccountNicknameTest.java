package com.example.toiletapi.auth.service;

import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.repository.AppUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountNicknameTest {
    final AppUserRepository users = mock(AppUserRepository.class);
    final AccountService service = new AccountService(users, null, null, null, null, null);

    @Test void trimsAndSavesOnlyAuthenticatedUser() {
        var user = AppUser.create("이전 이름", "unchanged@example.com", true);
        user.activateAfterConsent();
        when(users.findById(7L)).thenReturn(Optional.of(user));
        assertThat(service.updateNickname(7L, "  새 닉네임  ")).isEqualTo("새 닉네임");
        assertThat(user.getDisplayName()).isEqualTo("새 닉네임");
        assertThat(user.getEmail()).isEqualTo("unchanged@example.com");
        verify(users).findById(7L);
    }
    @Test void rejectsInvalidNicknameBeforeDatabaseAccess() {
        for (String input : new String[]{null, "", "  ", "가", "가".repeat(31), "가\n나", "가\u202E나"}) {
            assertThatThrownBy(() -> service.updateNickname(7L, input)).isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(400));
        }
        verifyNoInteractions(users);
    }
    @Test void rejectsPendingAndWithdrawnAccounts() {
        var user = AppUser.create("기존", null, false);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.updateNickname(7L, "새 이름")).isInstanceOf(ResponseStatusException.class);
        user.withdraw();
        assertThatThrownBy(() -> service.updateNickname(7L, "새 이름")).isInstanceOf(ResponseStatusException.class);
        assertThat(user.getDisplayName()).isEqualTo("탈퇴한 사용자");
    }
}
