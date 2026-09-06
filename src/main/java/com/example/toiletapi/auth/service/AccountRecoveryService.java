package com.example.toiletapi.auth.service;

import com.example.toiletapi.auth.model.*;
import com.example.toiletapi.auth.repository.*;
import com.example.toiletapi.global.time.KoreanTime;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountRecoveryService {
    private final AppUserRepository users;
    private final AccountWithdrawalRepository withdrawals;
    private final UserRoleAssignmentRepository roles;
    private final AuditLogService audit;
    private final AccountLifecycleGate lifecycle;
    public AccountRecoveryService(AppUserRepository users, AccountWithdrawalRepository withdrawals,
            UserRoleAssignmentRepository roles, AuditLogService audit, AccountLifecycleGate lifecycle) {
        this.users = users; this.withdrawals = withdrawals; this.roles = roles; this.audit = audit;
        this.lifecycle = lifecycle;
    }

    @Transactional(readOnly = true)
    public RecoveryStatus status(RecoveryChallengeStore.Proof proof) {
        var withdrawal = verified(proof);
        return new RecoveryStatus(withdrawal.getPurgeAfter(), withdrawal.getRecoveryDisplayName());
    }

    @Transactional
    public Long confirm(RecoveryChallengeStore.Proof proof) {
        lifecycle.requireRecovery();
        var user = users.lockById(proof.userId()).orElseThrow(RecoveryChallengeStore::expired);
        var withdrawal = verified(proof);
        if (user.getStatus() != UserStatus.WITHDRAWN) throw RecoveryChallengeStore.expired();
        user.restore(withdrawal.getRecoveryDisplayName());
        // Explicit USER provision prevents bootstrap allow-list from restoring ADMIN on the next OAuth login.
        roles.deleteAllByUserId(user.getId());
        roles.flush();
        roles.save(UserRoleAssignment.grant(user.getId(), Role.USER, null));
        withdrawals.delete(withdrawal);
        audit.record(user.getId(), AuditAction.USER_RESTORED, "USER", user.getId(), Map.of("source", "OAUTH_CONFIRMED"));
        return user.getId();
    }

    @Transactional
    public Long requestImmediateErasure(RecoveryChallengeStore.Proof proof) {
        lifecycle.requireRecovery();
        lifecycle.requireErasure();
        var user = users.lockById(proof.userId()).orElseThrow(RecoveryChallengeStore::expired);
        var withdrawal = verified(proof);
        if (user.getStatus() != UserStatus.WITHDRAWN) throw RecoveryChallengeStore.expired();
        withdrawal.requestErasure(KoreanTime.now());
        return user.getId();
    }

    private AccountWithdrawal verified(RecoveryChallengeStore.Proof proof) {
        lifecycle.requireRecovery();
        var withdrawal = withdrawals.findById(proof.userId()).orElseThrow(RecoveryChallengeStore::expired);
        if (!withdrawal.getWithdrawalKey().equals(proof.withdrawalKey()) || !withdrawal.canRecover(KoreanTime.now())) {
            throw RecoveryChallengeStore.expired();
        }
        return withdrawal;
    }
    public record RecoveryStatus(LocalDateTime purgeAfter, String displayName) { }
}
