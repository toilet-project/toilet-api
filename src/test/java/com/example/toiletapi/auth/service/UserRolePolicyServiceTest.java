package com.example.toiletapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.toiletapi.auth.config.AdminBootstrapProperties;
import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.model.Role;
import com.example.toiletapi.auth.model.UserRoleAssignment;
import com.example.toiletapi.auth.repository.UserRoleAssignmentRepository;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class UserRolePolicyServiceTest {

    private final UserRoleAssignmentRepository roleRepository = Mockito.mock(UserRoleAssignmentRepository.class);

    @Test
    void grantsUserAndAdminOnlyForVerifiedAllowListedEmail() throws Exception {
        UserRolePolicyService service = new UserRolePolicyService(
                roleRepository, new AdminBootstrapProperties("admin@geupddong.com"));
        AppUser user = persistedUser("admin@geupddong.com", true);
        when(roleRepository.findAllByUserId(1L)).thenReturn(List.of(
                UserRoleAssignment.grant(1L, Role.USER, null),
                UserRoleAssignment.grant(1L, Role.ADMIN, null)));

        assertThat(service.ensureInitialRoles(user)).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);

        ArgumentCaptor<UserRoleAssignment> captor = ArgumentCaptor.forClass(UserRoleAssignment.class);
        verify(roleRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(UserRoleAssignment::getRole)
                .containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
    }

    @Test
    void grantsOnlyUserForUnverifiedEmail() throws Exception {
        UserRolePolicyService service = new UserRolePolicyService(
                roleRepository, new AdminBootstrapProperties("admin@geupddong.com"));
        AppUser user = persistedUser("admin@geupddong.com", false);
        when(roleRepository.findAllByUserId(1L)).thenReturn(List.of(UserRoleAssignment.grant(1L, Role.USER, null)));

        assertThat(service.ensureInitialRoles(user)).containsExactly(Role.USER);
        ArgumentCaptor<UserRoleAssignment> captor = ArgumentCaptor.forClass(UserRoleAssignment.class);
        verify(roleRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.USER);
    }

    @Test
    void rejectsUserBeforePersistence() {
        UserRolePolicyService service = new UserRolePolicyService(
                roleRepository, new AdminBootstrapProperties("admin@geupddong.com"));

        assertThatThrownBy(() -> service.ensureInitialRoles(AppUser.create("운영자", "admin@geupddong.com", true)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(roleRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static AppUser persistedUser(String email, boolean emailVerified) throws Exception {
        AppUser user = AppUser.create("운영자", email, emailVerified);
        Field id = AppUser.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(user, 1L);
        return user;
    }
}
