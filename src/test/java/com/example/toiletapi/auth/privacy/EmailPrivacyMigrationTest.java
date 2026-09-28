package com.example.toiletapi.auth.privacy;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class EmailPrivacyMigrationTest {
    private final DriverManagerDataSource ds = com.example.toiletapi.auth.support.NativeMySqlFixture.enabled() ? com.example.toiletapi.auth.support.NativeMySqlFixture.create() : new DriverManagerDataSource(
            "jdbc:h2:mem:email-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    private final JdbcTemplate jdbc = new JdbcTemplate(ds);
    private final EmailProtection protection = new EmailProtection(EmailProtectionTest.settings());
    private final EmailPrivacyMigration migration = new EmailPrivacyMigration(jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(ds)), protection);
    EmailPrivacyMigrationTest() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__create_auth_data_model.sql"),
                new ClassPathResource("db/migration/V34__prepare_email_encryption.sql")).execute(ds);
    }
    private void seed(long id) {
        jdbc.update("INSERT INTO app_user(user_id,display_name,email,email_verified) VALUES(?,'name',?,TRUE)",id,"user"+id+"@example.test");
        jdbc.update("INSERT INTO user_social_account(user_id,provider,provider_subject_hash,provider_email) VALUES(?,'GOOGLE',?,?)",
                id,String.format("%064d",id),"user"+id+"@example.test");
        jdbc.update("INSERT INTO user_role(user_id,role) VALUES(?,'USER')",id);
    }
    @Test void dryRunApplyRestartAndIdentityPreservationAcrossBatches() {
        for(long id=1;id<=205;id++) seed(id);
        assertThat(migration.check().legacyUsers()).isEqualTo(205);
        assertThat(jdbc.queryForObject("SELECT email FROM app_user WHERE user_id=1",String.class)).isEqualTo("user1@example.test");
        var result = migration.apply();
        assertThat(result.legacyUsers()).isZero(); assertThat(result.legacySocials()).isZero();
        assertThat(result.protectedUsers()).isEqualTo(205); assertThat(result.protectedSocials()).isEqualTo(205);
        assertThat(migration.apply()).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_role",Long.class)).isEqualTo(205);
        assertThat(jdbc.queryForObject("SELECT user_id FROM app_user WHERE email_lookup_hash=?",Long.class,
                protection.lookupHash("USER1@EXAMPLE.TEST"))).isEqualTo(1);
        assertThat(protection.decrypt(jdbc.queryForObject("SELECT email_ciphertext FROM app_user WHERE user_id=1",String.class),
                EmailProtection.USER)).isEqualTo("user1@example.test");
    }
    @Test void verificationFailureStopsBeforeChangingAnyLegacyRow() {
        seed(1); migration.apply(); seed(2);
        jdbc.update("UPDATE app_user SET email_ciphertext='e1.UNKNOWN.broken' WHERE user_id=1");
        assertThatThrownBy(migration::apply).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT email FROM app_user WHERE user_id=2",String.class)).isEqualTo("user2@example.test");
    }
    @Test void failedBatchRollsBackBothCopiesAndCanResume() {
        seed(1);
        jdbc.execute("ALTER TABLE user_social_account ADD CONSTRAINT forced_failure CHECK(provider_email IS NOT NULL)");
        assertThatThrownBy(migration::apply).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT email FROM app_user WHERE user_id=1",String.class)).isEqualTo("user1@example.test");
        jdbc.execute("ALTER TABLE user_social_account DROP CONSTRAINT forced_failure");
        assertThat(migration.apply().legacyUsers()).isZero();
    }
}
