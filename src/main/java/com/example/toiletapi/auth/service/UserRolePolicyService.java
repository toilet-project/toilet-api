package com.example.toiletapi.auth.service;

import com.example.toiletapi.auth.config.AdminBootstrapProperties;
import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.model.Role;
import com.example.toiletapi.auth.model.UserRoleAssignment;
import com.example.toiletapi.auth.model.UserRoleId;
import com.example.toiletapi.auth.repository.UserRoleAssignmentRepository;
import java.util.EnumSet;
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

        grantWhenAbsent(user.getId(), Role.USER);
        if (adminBootstrapProperties.isBootstrapAdmin(user.getEmail(), user.isEmailVerified())) {
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

    private void grantWhenAbsent(Long userId, Role role) {
        UserRoleId id = new UserRoleId(userId, role);
        if (!roleRepository.existsById(id)) {
            roleRepository.save(UserRoleAssignment.grant(userId, role, null));
            auditLogService.recordRoleGranted(null, userId, role);
        }
    }
}
