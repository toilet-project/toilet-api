package com.example.toiletapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import com.example.toiletapi.auth.model.*;
import com.example.toiletapi.auth.repository.*;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class AdminSecurityServiceTest {
    private final AppUserRepository userRepository = Mockito.mock(AppUserRepository.class);
    private final UserRoleAssignmentRepository roleRepository = Mockito.mock(UserRoleAssignmentRepository.class);
    private final AuditLogRepository auditLogRepository = Mockito.mock(AuditLogRepository.class);
    private final UserRolePolicyService rolePolicyService = Mockito.mock(UserRolePolicyService.class);
    private final AdminSecurityService service = new AdminSecurityService(
            userRepository, roleRepository, auditLogRepository, rolePolicyService);

    @Test
    void returnsUsersWithRolesUsingServerPage() throws Exception {
        AppUser user = persistedUser(2L, "사용자", "user@example.com");
        when(userRepository.searchAdminUsers(eq("사용"), isNull(), eq(Role.ADMIN), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user)));
        when(roleRepository.findAllByUserIdIn(List.of(2L))).thenReturn(List.of(
                UserRoleAssignment.grant(2L, Role.USER, null),
                UserRoleAssignment.grant(2L, Role.ADMIN, 1L)));

        var result = service.users(" 사용 ", null, Role.ADMIN, 0, 20);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items().getFirst().roles()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
        assertThat(result.items().getFirst().email()).isEqualTo("user@example.com");
    }

    @Test
    void rejectsInvertedAuditDateRange() {
        assertThatThrownBy(() -> service.auditLogs(
                LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 1), null, null, null, null,
                "NEWEST", 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시작일");
    }

    @Test
    void returnsAuditActorNameWithoutEmail() throws Exception {
        AuditLog log = AuditLog.record(1L, AuditAction.REPORT_APPROVED, "TOILET_REPORT", 10L, "{\"reason\":\"확인\"}");
        AppUser actor = persistedUser(1L, "운영자", "admin@example.com");
        when(auditLogRepository.search(any(), any(), eq("REPORT_APPROVED"), eq(1L),
                eq("TOILET_REPORT"), eq(10L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(log)));
        when(userRepository.findAllById(any())).thenReturn(List.of(actor));

        var result = service.auditLogs(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2),
                AuditAction.REPORT_APPROVED, 1L, "toilet_report", 10L, "NEWEST", 0, 20);

        assertThat(result.items().getFirst().actorDisplayName()).isEqualTo("운영자");
        assertThat(result.items().getFirst().detailJson()).doesNotContain("admin@example.com");
    }

    private static AppUser persistedUser(Long idValue, String name, String emailValue) throws Exception {
        AppUser user = AppUser.create(name, emailValue, true);
        Field id = AppUser.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(user, idValue);
        return user;
    }
}
