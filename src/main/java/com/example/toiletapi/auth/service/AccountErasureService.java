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
    private final com.geupddong.account.ErasureLedger ledger;
    private final String realm;

    public AccountErasureService(AppUserRepository users, AccountWithdrawalRepository withdrawals,
            RefreshTokenStore refreshTokens, JdbcTemplate jdbc, EntityManager entityManager, RecoveryChallengeStore recoveryChallenges,
            com.geupddong.account.ErasureLedger ledger,
            @org.springframework.beans.factory.annotation.Value("${erasure.ledger.realm:production}") String realm) {
        this.users = users; this.withdrawals = withdrawals; this.refreshTokens = refreshTokens;
        this.jdbc = jdbc; this.entityManager = entityManager;
        this.recoveryChallenges = recoveryChallenges;
        this.ledger = ledger; this.realm = realm;
    }

    @Transactional
    public boolean eraseIfDue(Long id, LocalDateTime now) {
        var user = users.lockById(id).orElse(null);
        var withdrawal = withdrawals.findById(id).orElse(null);
        if (user == null || withdrawal == null || user.getStatus() != UserStatus.WITHDRAWN
                || now.isBefore(withdrawal.getPurgeAfter())) return false;
        var createdAt = jdbc.queryForObject("SELECT created_at FROM app_user WHERE user_id=?",
                LocalDateTime.class, id);
        ledger.ensureRecorded(new com.geupddong.account.ErasureRecord(1, realm, id, createdAt.toString(),
                withdrawal.getWithdrawalKey(), withdrawal.getPurgeAfter().toString()));
        refreshTokens.deleteAllForUser(id); // Fail closed on Redis failure; DB work remains retryable.
        recoveryChallenges.deleteAllForUser(id);
        entityManager.flush();

        com.geupddong.account.AccountErasureSql.erase(jdbc, id);
        entityManager.clear();
        return true;
    }

    @Transactional
    public void recordFailure(Long id) {
        if (users.lockById(id).isEmpty()) return;
        withdrawals.findById(id).ifPresent(w -> w.failed(KoreanTime.now()));
    }
}
