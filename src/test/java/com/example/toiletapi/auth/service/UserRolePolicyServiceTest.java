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
    private final AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

    @Test
    void grantsUserAndAdminOnlyForVerifiedAllowListedEmail() throws Exception {
        UserRolePolicyService service = new UserRolePolicyService(
                roleRepository, new AdminBootstrapProperties("admin@geupddong.com"), auditLogService);
        AppUser user = persistedUser("admin@geupddong.com", true);
        when(roleRepository.findAllByUserId(1L)).thenReturn(
                List.of(),
                List.of(UserRoleAssignment.grant(1L, Role.USER, null),
                        UserRoleAssignment.grant(1L, Role.ADMIN, null)));

        assertThat(service.ensureInitialRoles(user)).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);

        ArgumentCaptor<UserRoleAssignment> captor = ArgumentCaptor.forClass(UserRoleAssignment.class);
        verify(roleRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(UserRoleAssignment::getRole)
                .containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
        verify(auditLogService).recordRoleGranted(null, 1L, Role.USER);
        verify(auditLogService).recordRoleGranted(null, 1L, Role.ADMIN);
    }

    @Test
    void grantsOnlyUserForUnverifiedEmail() throws Exception {
        UserRolePolicyService service = new UserRolePolicyService(
                roleRepository, new AdminBootstrapProperties("admin@geupddong.com"), auditLogService);
        AppUser user = persistedUser("admin@geupddong.com", false);
        when(roleRepository.findAllByUserId(1L)).thenReturn(
                List.of(), List.of(UserRoleAssignment.grant(1L, Role.USER, null)));

        assertThat(service.ensureInitialRoles(user)).containsExactly(Role.USER);
        ArgumentCaptor<UserRoleAssignment> captor = ArgumentCaptor.forClass(UserRoleAssignment.class);
        verify(roleRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.USER);
    }

    @Test
    void rejectsUserBeforePersistence() {
        UserRolePolicyService service = new UserRolePolicyService(
                roleRepository, new AdminBootstrapProperties("admin@geupddong.com"), auditLogService);

        assertThatThrownBy(() -> service.ensureInitialRoles(AppUser.create("운영자", "admin@geupddong.com", true)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(roleRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void grantsAdminWithActorAndAuditEvent() {
        UserRolePolicyService service = new UserRolePolicyService(
                roleRepository, new AdminBootstrapProperties(""), auditLogService);
        when(roleRepository.findAllByUserId(2L)).thenReturn(List.of(
                UserRoleAssignment.grant(2L, Role.USER, null),
                UserRoleAssignment.grant(2L, Role.ADMIN, 1L)));

        assertThat(service.grantAdmin(1L, 2L)).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);

        ArgumentCaptor<UserRoleAssignment> captor = ArgumentCaptor.forClass(UserRoleAssignment.class);
        verify(roleRepository).save(captor.capture());
        assertThat(captor.getValue().getGrantedByUserId()).isEqualTo(1L);
        verify(auditLogService).recordRoleGranted(1L, 2L, Role.ADMIN);
    }

    @Test
    void rejectsRevokingOwnAdminRole() {
        UserRolePolicyService service = new UserRolePolicyService(
                roleRepository, new AdminBootstrapProperties(""), auditLogService);

        assertThatThrownBy(() -> service.revokeAdmin(1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("자신의 관리자 권한");
        verify(roleRepository, never()).deleteById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void revokesAdminWhenAnotherAdministratorRemains() {
        UserRolePolicyService service = new UserRolePolicyService(
                roleRepository, new AdminBootstrapProperties(""), auditLogService);
        when(roleRepository.existsById(new com.example.toiletapi.auth.model.UserRoleId(2L, Role.ADMIN))).thenReturn(true);
        when(roleRepository.countByRole(Role.ADMIN)).thenReturn(2L);
        when(roleRepository.findAllByUserId(2L)).thenReturn(List.of(UserRoleAssignment.grant(2L, Role.USER, null)));

        assertThat(service.revokeAdmin(1L, 2L)).containsExactly(Role.USER);

        verify(roleRepository).deleteById(new com.example.toiletapi.auth.model.UserRoleId(2L, Role.ADMIN));
        verify(auditLogService).recordRoleRevoked(1L, 2L, Role.ADMIN);
    }

    private static AppUser persistedUser(String email, boolean emailVerified) throws Exception {
        AppUser user = AppUser.create("운영자", email, emailVerified);
        Field id = AppUser.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(user, 1L);
        return user;
    }
}
