package com.example.toiletapi.auth.service;

import com.example.toiletapi.auth.dto.*;
import com.example.toiletapi.auth.model.*;
import com.example.toiletapi.auth.repository.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminSecurityService {
    private static final int MAX_PAGE_SIZE = 50;
    private final AppUserRepository userRepository;
    private final UserRoleAssignmentRepository roleRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRolePolicyService rolePolicyService;

    public AdminSecurityService(AppUserRepository userRepository, UserRoleAssignmentRepository roleRepository,
                                AuditLogRepository auditLogRepository, UserRolePolicyService rolePolicyService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.auditLogRepository = auditLogRepository;
        this.rolePolicyService = rolePolicyService;
    }

    @Transactional(readOnly = true)
    public AdminUserPageResponse users(String keyword, UserStatus status, Role role, int page, int size) {
        PageRequest pageable = PageRequest.of(validPage(page), validSize(size), Sort.by(Sort.Direction.DESC, "createdAt"));
        var users = userRepository.searchAdminUsers(blankToNull(keyword), status, role, pageable);
        Map<Long, Set<Role>> rolesByUser = roleRepository.findAllByUserIdIn(
                        users.getContent().stream().map(AppUser::getId).toList())
                .stream().collect(Collectors.groupingBy(UserRoleAssignment::getUserId,
                        Collectors.mapping(UserRoleAssignment::getRole, Collectors.toCollection(() -> EnumSet.noneOf(Role.class)))));
        var items = users.getContent().stream()
                .map(user -> AdminUserResponse.from(user, Set.copyOf(rolesByUser.getOrDefault(user.getId(), Set.of()))))
                .toList();
        return new AdminUserPageResponse(items, users.getNumber(), users.getSize(), users.getTotalElements(), users.getTotalPages());
    }

    @Transactional
    public AdminUserResponse grantAdmin(Long actorUserId, Long targetUserId) {
        AppUser user = requireUser(targetUserId);
        Set<Role> roles = rolePolicyService.grantAdmin(actorUserId, targetUserId);
        return AdminUserResponse.from(user, roles);
    }

    @Transactional
    public AdminUserResponse revokeAdmin(Long actorUserId, Long targetUserId) {
        AppUser user = requireUser(targetUserId);
        Set<Role> roles = rolePolicyService.revokeAdmin(actorUserId, targetUserId);
        return AdminUserResponse.from(user, roles);
    }

    @Transactional(readOnly = true)
    public AuditLogPageResponse auditLogs(LocalDate from, LocalDate to, AuditAction action, Long actorUserId,
                                          String targetType, Long targetId, String sort, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("시작일은 종료일보다 늦을 수 없습니다.");
        }
        Sort.Direction direction = "OLDEST".equalsIgnoreCase(sort) ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageable = PageRequest.of(validPage(page), validSize(size), Sort.by(direction, "createdAt"));
        LocalDateTime fromInclusive = from == null ? null : from.atStartOfDay();
        LocalDateTime toExclusive = to == null ? null : to.plusDays(1).atStartOfDay();
        var logs = auditLogRepository.search(fromInclusive, toExclusive, action == null ? null : action.name(),
                actorUserId, upperToNull(targetType), targetId, pageable);
        Set<Long> actorIds = logs.getContent().stream().map(AuditLog::getActorUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> actorNames = userRepository.findAllById(actorIds).stream().collect(Collectors.toMap(
                AppUser::getId, user -> Optional.ofNullable(user.getDisplayName()).filter(name -> !name.isBlank()).orElse("사용자 #" + user.getId())));
        var items = logs.getContent().stream().map(log -> AuditLogResponse.from(log,
                log.getActorUserId() == null ? "시스템" : actorNames.getOrDefault(log.getActorUserId(), "사용자 #" + log.getActorUserId()))).toList();
        return new AuditLogPageResponse(items, logs.getNumber(), logs.getSize(), logs.getTotalElements(), logs.getTotalPages());
    }

    private AppUser requireUser(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("대상 사용자를 찾을 수 없습니다."));
    }

    private int validPage(int page) { return Math.max(page, 0); }
    private int validSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("페이지 크기는 1~50이어야 합니다.");
        return size;
    }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String upperToNull(String value) { String normalized = blankToNull(value); return normalized == null ? null : normalized.toUpperCase(Locale.ROOT); }
}
