package com.example.toiletapi.auth.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AppUserTest {

    @Test
    void refreshesVerifiedEmailFromOAuthBeforeRolePolicyEvaluation() {
        AppUser user = AppUser.create("기존 이름", "admin@geupddong.com", false);

        user.refreshOAuthProfile("Google 운영자", "admin@geupddong.com", true);

        assertThat(user.getDisplayName()).isEqualTo("기존 이름");
        assertThat(user.getEmail()).isEqualTo("admin@geupddong.com");
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getLastLoginAt()).isNotNull();
    }

    @Test
    void keepsEditedNicknameAfterSubsequentSocialLogin() {
        AppUser user = AppUser.create("소셜 이름", "user@example.com", false);
        user.changeDisplayName("내 닉네임");
        user.refreshOAuthProfile("바뀐 소셜 이름", "user@example.com", true);
        assertThat(user.getDisplayName()).isEqualTo("내 닉네임");
        assertThat(user.isEmailVerified()).isTrue();
    }
}
