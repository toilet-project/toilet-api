package com.example.toiletapi.auth.service;

import com.example.toiletapi.auth.model.UserStatus;
import com.example.toiletapi.auth.repository.*;
import com.example.toiletapi.global.time.KoreanTime;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One account per transaction. Unknown FK/SQL failures roll back ALL database erasure. */
@Service
public class AccountErasureService {
    private final AppUserRepository users;
    private final AccountWithdrawalRepository withdrawals;
    private final RefreshTokenStore refreshTokens;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final RecoveryChallengeStore recoveryChallenges;

    public AccountErasureService(AppUserRepository users, AccountWithdrawalRepository withdrawals,
            RefreshTokenStore refreshTokens, JdbcTemplate jdbc, EntityManager entityManager, RecoveryChallengeStore recoveryChallenges) {
        this.users = users; this.withdrawals = withdrawals; this.refreshTokens = refreshTokens;
        this.jdbc = jdbc; this.entityManager = entityManager;
        this.recoveryChallenges = recoveryChallenges;
    }

    @Transactional
    public boolean eraseIfDue(Long id, LocalDateTime now) {
        var user = users.lockById(id).orElse(null);
        var withdrawal = withdrawals.findById(id).orElse(null);
        if (user == null || withdrawal == null || user.getStatus() != UserStatus.WITHDRAWN
                || now.isBefore(withdrawal.getPurgeAfter())) return false;
        refreshTokens.deleteAllForUser(id); // Fail closed on Redis failure; DB work remains retryable.
        recoveryChallenges.deleteAllForUser(id);
        entityManager.flush();

        // Free text may contain a name/contact. Keep structured toilet corrections, not author free text.
        jdbc.update("UPDATE audit_log SET detail_json=NULL WHERE target_type='TOILET_REPORT' AND target_id IN "
                + "(SELECT report_id FROM toilet_report WHERE reporter_user_id=?)", id);
        jdbc.update("UPDATE toilet_report SET reporter_user_id=NULL, reason='탈퇴한 사용자 — 사유 파기', "
                + "review_note=NULL, active_request_key=NULL WHERE reporter_user_id=?", id);
        jdbc.update("UPDATE toilet_report SET reviewed_by_user_id=NULL, review_note=NULL WHERE reviewed_by_user_id=?", id);
        jdbc.update("UPDATE coordinate_revision SET applied_by_user_id=NULL WHERE applied_by_user_id=?", id);
        jdbc.update("UPDATE coordinate_quality_review SET reviewed_by_user_id=NULL, review_note=NULL WHERE reviewed_by_user_id=?", id);
        jdbc.update("UPDATE user_role SET granted_by_user_id=NULL WHERE granted_by_user_id=?", id);
        jdbc.update("UPDATE audit_log SET actor_user_id=NULL, actor_erased=TRUE, detail_json=NULL WHERE actor_user_id=?", id);
        jdbc.update("UPDATE audit_log SET target_id=NULL, detail_json=NULL WHERE target_type='USER' AND target_id=?", id);
        jdbc.update("DELETE FROM user_notification WHERE user_id=?", id);
        jdbc.update("DELETE FROM user_policy_consent WHERE user_id=?", id);
        jdbc.update("DELETE FROM user_role WHERE user_id=?", id);
        jdbc.update("DELETE FROM user_social_account WHERE user_id=?", id);
        jdbc.update("DELETE FROM account_withdrawal WHERE user_id=?", id);
        jdbc.update("DELETE FROM app_user WHERE user_id=? AND status='WITHDRAWN'", id);
        entityManager.clear();
        return true;
    }

    @Transactional
    public void recordFailure(Long id) {
        if (users.lockById(id).isEmpty()) return;
        withdrawals.findById(id).ifPresent(w -> w.failed(KoreanTime.now()));
    }
}
