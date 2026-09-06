package com.example.toiletapi.auth.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

@Entity @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "account_withdrawal")
public class AccountWithdrawal {
    public static final String CONSENT_VERSION = "recovery-2026-09-v1";
    @Id @Column(name = "user_id") private Long userId;
    @Column(name = "withdrawal_key", nullable = false, length = 36) private String withdrawalKey;
    @Column(name = "withdrawn_at", nullable = false) private LocalDateTime withdrawnAt;
    @Column(name = "purge_after", nullable = false) private LocalDateTime purgeAfter;
    @Column(name = "recovery_allowed", nullable = false) private boolean recoveryAllowed;
    @Column(name = "consent_version", length = 30) private String consentVersion;
    @Column(name = "recovery_display_name", length = 100) private String recoveryDisplayName;
    @Column(nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private LocalDateTime nextAttemptAt;
    @Column(name = "last_failure_code", length = 50) private String lastFailureCode;

    public static AccountWithdrawal create(AppUser user, boolean recovery, LocalDateTime now) {
        AccountWithdrawal record = new AccountWithdrawal();
        record.userId = user.getId();
        record.withdrawalKey = UUID.randomUUID().toString();
        record.withdrawnAt = now;
        record.purgeAfter = recovery ? now.plusMonths(3) : now;
        record.nextAttemptAt = record.purgeAfter;
        record.recoveryAllowed = recovery;
        record.consentVersion = recovery ? CONSENT_VERSION : null;
        record.recoveryDisplayName = recovery ? user.getDisplayName() : null;
        return record;
    }
    public boolean canRecover(LocalDateTime now) { return recoveryAllowed && now.isBefore(purgeAfter); }
    public void requestErasure(LocalDateTime now) {
        recoveryAllowed = false; recoveryDisplayName = null;
        purgeAfter = now; nextAttemptAt = now;
    }
    public void failed(LocalDateTime now) {
        attempts++;
        lastFailureCode = "ERASURE_RETRY_REQUIRED";
        nextAttemptAt = now.plusMinutes(Math.min(60, 1L << Math.min(attempts, 6)));
    }
}
