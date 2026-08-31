package com.example.toiletapi.auth.service;

import com.example.toiletapi.auth.config.AdminBootstrapProperties;
import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.model.Role;
import com.example.toiletapi.auth.model.UserRoleAssignment;
import com.example.toiletapi.auth.model.UserRoleId;
import com.example.toiletapi.auth.repository.UserRoleAssignmentRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** OAuth 로그인으로 생성되거나 연결된 사용자에게 역할을 부여하는 정책 경계입니다. */
@Service
public class UserRolePolicyService {

    private final UserRoleAssignmentRepository roleRepository;
    private final AdminBootstrapProperties adminBootstrapProperties;
    private final AuditLogService auditLogService;

    public UserRolePolicyService(
            UserRoleAssignmentRepository roleRepository,
            AdminBootstrapProperties adminBootstrapProperties,
            AuditLogService auditLogService
    ) {
        this.roleRepository = roleRepository;
        this.adminBootstrapProperties = adminBootstrapProperties;
        this.auditLogService = auditLogService;
    }

    /**
     * 모든 사용자에게 USER 역할을 보장하고, 환경변수 allow-list와 이메일 인증을 모두 만족하는 경우에만
     * 최초 ADMIN 역할을 추가합니다.
     */
    @Transactional
    public Set<Role> ensureInitialRoles(AppUser user) {
        if (user.getId() == null) {
            throw new IllegalArgumentException("저장되지 않은 사용자에게 역할을 부여할 수 없습니다.");
        }

        List<UserRoleAssignment> existingRoles = roleRepository.findAllByUserId(user.getId());
        boolean firstRoleProvisioning = existingRoles.isEmpty();
        grantWhenAbsent(user.getId(), Role.USER, null);
        if (firstRoleProvisioning && adminBootstrapProperties.isBootstrapAdmin(user.getEmail(), user.isEmailVerified())) {
            grantWhenAbsent(user.getId(), Role.ADMIN);
        }
        return rolesOf(user.getId());
    }

    @Transactional(readOnly = true)
    public Set<Role> rolesOf(Long userId) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        roleRepository.findAllByUserId(userId).forEach(assignment -> roles.add(assignment.getRole()));
        return Set.copyOf(roles);
    }

    @Transactional
    public Set<Role> grantAdmin(Long actorUserId, Long targetUserId) {
        if (actorUserId == null || targetUserId == null) {
            throw new IllegalArgumentException("관리자와 대상 사용자 식별자가 필요합니다.");
        }
        grantWhenAbsent(targetUserId, Role.ADMIN, actorUserId);
        return rolesOf(targetUserId);
    }

    @Transactional
    public Set<Role> revokeAdmin(Long actorUserId, Long targetUserId) {
        if (actorUserId == null || targetUserId == null) {
            throw new IllegalArgumentException("관리자와 대상 사용자 식별자가 필요합니다.");
        }
        if (actorUserId.equals(targetUserId)) {
            throw new IllegalArgumentException("자신의 관리자 권한은 직접 회수할 수 없습니다.");
        }
        UserRoleId roleId = new UserRoleId(targetUserId, Role.ADMIN);
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("대상 사용자에게 관리자 권한이 없습니다.");
        }
        if (roleRepository.countByRole(Role.ADMIN) <= 1) {
            throw new IllegalArgumentException("마지막 관리자 권한은 회수할 수 없습니다.");
        }
        roleRepository.deleteById(roleId);
        auditLogService.recordRoleRevoked(actorUserId, targetUserId, Role.ADMIN);
        return rolesOf(targetUserId);
    }

    private void grantWhenAbsent(Long userId, Role role) {
        grantWhenAbsent(userId, role, null);
    }

    private void grantWhenAbsent(Long userId, Role role, Long grantedByUserId) {
        UserRoleId id = new UserRoleId(userId, role);
        if (!roleRepository.existsById(id)) {
            roleRepository.save(UserRoleAssignment.grant(userId, role, grantedByUserId));
            auditLogService.recordRoleGranted(grantedByUserId, userId, role);
        }
    }
}
