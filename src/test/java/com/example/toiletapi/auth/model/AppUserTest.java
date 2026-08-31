package com.example.toiletapi.auth.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AppUserTest {

    @Test
    void refreshesVerifiedEmailFromOAuthBeforeRolePolicyEvaluation() {
        AppUser user = AppUser.create("기존 이름", "admin@geupddong.com", false);

        user.refreshOAuthProfile("Google 운영자", "admin@geupddong.com", true);

        assertThat(user.getDisplayName()).isEqualTo("Google 운영자");
        assertThat(user.getEmail()).isEqualTo("admin@geupddong.com");
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getLastLoginAt()).isNotNull();
    }
}
