package com.example.toiletapi.auth.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.toiletapi.auth.model.*;
import com.example.toiletapi.auth.model.Role;
import com.example.toiletapi.auth.repository.*;
import com.example.toiletapi.policy.repository.UserPolicyConsentRepository;
import com.example.toiletapi.global.time.KoreanTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** H2 by default; opt-in native MySQL requires the guarded, synthetic-only local test instance. */
@SpringJUnitConfig(AccountLifecycleIntegrationTest.Config.class)
class AccountLifecycleIntegrationTest {
    @Configuration @EnableTransactionManagement
    @EnableJpaRepositories(basePackages = {"com.example.toiletapi.auth.repository", "com.example.toiletapi.policy.repository"})
    @Import({AccountService.class, AccountRecoveryService.class, AccountErasureService.class, AuditLogService.class})
    static class Config {
        @Bean DataSource dataSource() {
            var ds = com.example.toiletapi.auth.support.NativeMySqlFixture.enabled()
                    ? com.example.toiletapi.auth.support.NativeMySqlFixture.create()
                    : new DriverManagerDataSource("jdbc:h2:mem:withdrawal;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
            new JdbcTemplate(ds).execute("CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY)");
            for (String file : new String[]{"V1__create_auth_data_model.sql", "V2__create_toilet_report_and_coordinate_revision.sql",
                    "V4__create_user_notification.sql", "V5__create_coordinate_quality_review.sql",
                    "V7__create_policy_consent_model.sql", "V11__account_withdrawal_retention.sql"}) {
                new ResourceDatabasePopulator(new ClassPathResource("db/migration/" + file)).execute(ds);
            }
            return ds;
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(ds);
            factory.setPackagesToScan("com.example.toiletapi.auth.model", "com.example.toiletapi.policy.model");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none")); return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory emf) { return new JpaTransactionManager(emf); }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean RefreshTokenStore refreshTokenStore() { return mock(RefreshTokenStore.class); }
        @Bean RecoveryChallengeStore recoveryChallengeStore() { return mock(RecoveryChallengeStore.class); }
        @Bean com.geupddong.account.ErasureLedger erasureLedger() { return mock(com.geupddong.account.ErasureLedger.class); }
    }
    @Autowired AppUserRepository users;
    @Autowired UserSocialAccountRepository socials;
    @Autowired UserRoleAssignmentRepository roles;
    @Autowired AccountWithdrawalRepository withdrawals;
    @Autowired AccountService accounts;
    @Autowired AccountRecoveryService recovery;
    @Autowired AccountErasureService erasure;
    @Autowired RefreshTokenStore refresh;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.geupddong.account.ErasureLedger ledger;

    @BeforeEach void resetRefresh() { reset(refresh, ledger); }
    Long fixture() {
        Long id = new TransactionTemplate(transactionManager).execute(tx -> {
            AppUser user = users.saveAndFlush(AppUser.create("원래 닉네임", "private@example.test", true));
            user.activateAfterConsent();
            socials.save(UserSocialAccount.link(user, SocialProvider.GOOGLE, String.format("%064d", user.getId()), "private@example.test"));
            roles.save(UserRoleAssignment.grant(user.getId(), Role.ADMIN, null));
            return user.getId();
        });
        jdbc.update("INSERT INTO toilet(toilet_id) VALUES(?)", id);
        jdbc.update("INSERT INTO toilet_report(toilet_id,reporter_user_id,report_type,reason,proposed_road_address) VALUES(?,?,'COORDINATE_CORRECTION','전화 private@example.test','대전광역시 유성구 공공시설')", id, id);
        jdbc.update("INSERT INTO user_notification(user_id,notification_type,reference_type,reference_id,title,message) VALUES(?,'REPORT_APPROVED','TOILET_REPORT',?,'결과','알림')", id, id);
        jdbc.update("INSERT INTO user_policy_consent(user_id,policy_document_id,consent_source) VALUES(?,1,'WEB_OAUTH_ONBOARDING')", id);
        return id;
    }
    @Test void calendarMonthsNotNinetyDaysAndBoundaryIsExclusive() {
        var user = AppUser.create("회원", null, false);
        var start = LocalDateTime.of(2026, 1, 31, 12, 0);
        var w = AccountWithdrawal.create(user, true, start);
        assertThat(w.getPurgeAfter()).isEqualTo(LocalDateTime.of(2026, 4, 30, 12, 0));
        assertThat(w.canRecover(w.getPurgeAfter().minusNanos(1))).isTrue();
        assertThat(w.canRecover(w.getPurgeAfter())).isFalse();
    }
    @Test void retentionIsOptionalAndUnknownConsentVersionCannotWithdraw() {
        Long id = fixture();
        assertThatThrownBy(() -> accounts.withdraw(id, true, "old")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(users.findById(id).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(withdrawals.existsById(id)).isFalse();
    }
    @Test void keepsOnlyRecoveryIdentityAndDoesNotRenewDeadlineOnRepeat() {
        Long id = fixture(); accounts.withdraw(id, true, AccountWithdrawal.CONSENT_VERSION);
        var w = withdrawals.findById(id).orElseThrow();
        var user = users.findById(id).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.getDisplayName()).isEqualTo("탈퇴한 사용자");
        assertThat(user.getEmail()).isNull(); assertThat(user.getLastLoginAt()).isNull();
        assertThat(user.getAuthVersion()).isEqualTo(1);
        assertThat(socials.findAllByUserId(id)).singleElement().satisfies(s -> assertThat(s.getProviderEmail()).isNull());
        assertThat(roles.findAllByUserId(id)).isEmpty();
        accounts.withdraw(id, true, AccountWithdrawal.CONSENT_VERSION);
        assertThat(withdrawals.findById(id).orElseThrow().getPurgeAfter()).isEqualTo(w.getPurgeAfter());
        verify(refresh).deleteAllForUser(id);
    }
    @Test void restoresSameIdReportsNicknameButOnlyUserRoleAndFreshConsent() {
        Long id = fixture(); accounts.withdraw(id, true, AccountWithdrawal.CONSENT_VERSION);
        var w = withdrawals.findById(id).orElseThrow();
        var proof = new RecoveryChallengeStore.Proof(id, w.getWithdrawalKey());
        assertThat(recovery.confirm(proof)).isEqualTo(id);
        var user = users.findById(id).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_CONSENT);
        assertThat(user.getDisplayName()).isEqualTo("원래 닉네임");
        assertThat(user.getAuthVersion()).isEqualTo(2);
        assertThat(roles.findAllByUserId(id)).extracting(UserRoleAssignment::getRole).containsExactly(Role.USER);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM toilet_report WHERE reporter_user_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_policy_consent WHERE user_id=? AND withdrawn_at IS NULL", Integer.class, id)).isZero();
        assertThatThrownBy(() -> recovery.confirm(proof)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void noConsentErasesAccountButPreservesStructuredReport() {
        Long id = fixture(); accounts.withdraw(id, false, null);
        assertThat(erasure.eraseIfDue(id, KoreanTime.now())).isTrue();
        assertErased(id);
        assertThat(erasure.eraseIfDue(id, KoreanTime.now())).isFalse();
    }
    @Test void expiredAccountsCannotRecoverAndAreErasedAfterSchedulerDowntime() {
        Long id = fixture(); accounts.withdraw(id, true, AccountWithdrawal.CONSENT_VERSION);
        var w = withdrawals.findById(id).orElseThrow();
        var now = KoreanTime.now();
        jdbc.update("UPDATE account_withdrawal SET purge_after=?,next_attempt_at=? WHERE user_id=?", now.minusDays(1), now.minusDays(1), id);
        assertThatThrownBy(() -> recovery.confirm(new RecoveryChallengeStore.Proof(id, w.getWithdrawalKey()))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(withdrawals.findDue(now, org.springframework.data.domain.PageRequest.of(0, 50))).contains(id);
        assertThat(erasure.eraseIfDue(id, now)).isTrue(); assertErased(id);
    }
    @Test void earlyErasureRequiresValidFreshProof() {
        Long id = fixture(); accounts.withdraw(id, true, AccountWithdrawal.CONSENT_VERSION);
        assertThat(erasure.eraseIfDue(id, KoreanTime.now())).isFalse();
        assertThatThrownBy(() -> recovery.requestImmediateErasure(new RecoveryChallengeStore.Proof(id, "wrong"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var w = withdrawals.findById(id).orElseThrow();
        recovery.requestImmediateErasure(new RecoveryChallengeStore.Proof(id, w.getWithdrawalKey()));
        assertThat(withdrawals.findById(id).orElseThrow().isRecoveryAllowed()).isFalse();
        assertThat(erasure.eraseIfDue(id, KoreanTime.now())).isTrue(); assertErased(id);
    }
    @Test void unknownForeignKeyRollsBackEntireErasureAndCheckpointsRetry() {
        Long id = fixture(); accounts.withdraw(id, false, null);
        jdbc.execute("CREATE TABLE erasure_blocker(user_id BIGINT, FOREIGN KEY(user_id) REFERENCES app_user(user_id))");
        try {
            jdbc.update("INSERT INTO erasure_blocker VALUES(?)", id);
            assertThatThrownBy(() -> erasure.eraseIfDue(id, KoreanTime.now())).isInstanceOf(RuntimeException.class);
            assertThat(users.existsById(id)).isTrue();
            assertThat(jdbc.queryForObject("SELECT reason FROM toilet_report WHERE reporter_user_id=?", String.class, id)).contains("private@example.test");
            erasure.recordFailure(id);
            assertThat(withdrawals.findById(id).orElseThrow().getAttempts()).isEqualTo(1);
            assertThat(withdrawals.findById(id).orElseThrow().getNextAttemptAt()).isAfter(KoreanTime.now());
        } finally { jdbc.execute("DROP TABLE erasure_blocker"); }
        assertThat(erasure.eraseIfDue(id, KoreanTime.now())).isTrue(); assertErased(id);
    }
    @Test void redisFailureDoesNotDeleteDatabaseInformation() {
        Long id = fixture(); accounts.withdraw(id, false, null);
        doThrow(new IllegalStateException("Redis unavailable")).when(refresh).deleteAllForUser(id);
        assertThatThrownBy(() -> erasure.eraseIfDue(id, KoreanTime.now())).isInstanceOf(IllegalStateException.class);
        assertThat(users.existsById(id)).isTrue(); assertThat(withdrawals.existsById(id)).isTrue();
    }
    @Test void oldWithdrawnUsersAreNotSilentlyEnrolled() {
        Long id = fixture();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> users.findById(id).orElseThrow().withdraw());
        assertThat(withdrawals.existsById(id)).isFalse();
        assertThat(erasure.eraseIfDue(id, KoreanTime.now().plusYears(1))).isFalse();
    }
    @Test void externalLedgerFailurePreventsRedisAndDatabaseErasure() {
        Long id = fixture(); accounts.withdraw(id, false, null);
        reset(refresh);
        doThrow(new IllegalStateException("ERASURE_LEDGER_UNAVAILABLE")).when(ledger).ensureRecorded(any());
        assertThatThrownBy(() -> erasure.eraseIfDue(id, KoreanTime.now())).isInstanceOf(IllegalStateException.class);
        assertThat(users.existsById(id)).isTrue();
        assertThat(withdrawals.existsById(id)).isTrue();
        verifyNoInteractions(refresh);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM toilet_report WHERE reporter_user_id=?", Integer.class, id)).isEqualTo(1);
    }
    @Test void backupReplayDryRunThenApplyErasesOnlyRecordedOriginalIdentity() {
        Long erasedId = fixture(), retainedId = fixture();
        var entry = new com.geupddong.account.ErasureRecord(1, "production", erasedId,
                jdbc.queryForObject("SELECT created_at FROM app_user WHERE user_id=?", java.sql.Timestamp.class, erasedId).toLocalDateTime().toString(),
                java.util.UUID.randomUUID().toString(), KoreanTime.now().minusDays(1).toString());
        var restore = new com.geupddong.account.AccountErasureRestore(jdbc, transactionManager);
        var plan = restore.replay(java.util.List.of(entry), "production", KoreanTime.now(), false);
        assertThat(plan.matched()).isEqualTo(1); assertThat(plan.erased()).isZero();
        assertThat(users.findById(erasedId).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        var result = restore.replay(java.util.List.of(entry), "production", KoreanTime.now(), true);
        assertThat(result.erased()).isEqualTo(1); assertErased(erasedId);
        assertThat(users.existsById(retainedId)).isTrue();
        assertThat(restore.replay(java.util.List.of(entry), "production", KoreanTime.now(), true).absent()).isEqualTo(1);
    }
    @Test void backupReplayIdentityConflictRejectsWholeTransactionBeforeErasure() {
        Long first = fixture(), second = fixture();
        String created = jdbc.queryForObject("SELECT created_at FROM app_user WHERE user_id=?", java.sql.Timestamp.class, first).toLocalDateTime().toString();
        var firstEntry = new com.geupddong.account.ErasureRecord(1, "production", first, created,
                java.util.UUID.randomUUID().toString(), KoreanTime.now().minusDays(1).toString());
        var conflicting = new com.geupddong.account.ErasureRecord(1, "production", second, "2000-01-01T00:00",
                java.util.UUID.randomUUID().toString(), KoreanTime.now().minusDays(1).toString());
        var restore = new com.geupddong.account.AccountErasureRestore(jdbc, transactionManager);
        assertThatThrownBy(() -> restore.replay(java.util.List.of(firstEntry, conflicting), "production", KoreanTime.now(), true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(users.existsById(first)).isTrue(); assertThat(users.existsById(second)).isTrue();
    }
    void assertErased(Long id) {
        assertThat(users.existsById(id)).isFalse(); assertThat(withdrawals.existsById(id)).isFalse();
        for (String table : new String[]{"user_social_account", "user_role", "user_notification", "user_policy_consent"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Integer.class, id)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT reason FROM toilet_report WHERE toilet_id=?", String.class, id)).isEqualTo("탈퇴한 사용자 — 사유 파기");
        assertThat(jdbc.queryForObject("SELECT reporter_user_id FROM toilet_report WHERE toilet_id=?", Long.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT proposed_road_address FROM toilet_report WHERE toilet_id=?", String.class, id)).isEqualTo("대전광역시 유성구 공공시설");
    }
}
