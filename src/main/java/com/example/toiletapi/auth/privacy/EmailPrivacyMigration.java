package com.example.toiletapi.auth.privacy;

import java.util.List;
import java.util.Objects;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit maintenance command, never scheduled or executed during API startup. No addresses in output. */
public final class EmailPrivacyMigration {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final EmailProtection protection;

    public EmailPrivacyMigration(JdbcTemplate jdbc, TransactionTemplate transaction, EmailProtection protection) {
        this.jdbc = jdbc; this.transaction = transaction; this.protection = protection;
    }

    public record Summary(long legacyUsers, long legacySocials, long protectedUsers, long protectedSocials) { }
    private record UserRow(long id, String raw, String cipher, String hash, String masked) { }
    private record SocialRow(long id, String raw, String cipher) { }

    /** Validates every encrypted value before allowing any writes. Also useful after a restore. */
    public Summary check() {
        long cursor = 0;
        long plainUsers = 0, encryptedUsers = 0, plainSocials = 0, encryptedSocials = 0;
        while (true) {
            List<UserRow> users = usersAfter(cursor, false);
            if (users.isEmpty()) break;
            for (UserRow user : users) {
                if (user.raw != null) plainUsers++;
                if (user.cipher != null) {
                    String value = protection.decrypt(user.cipher, EmailProtection.USER);
                    if (!Objects.equals(user.hash, protection.lookupHash(value))
                            || !Objects.equals(user.masked, EmailMask.mask(value))
                            || user.raw != null) throw invalid();
                    encryptedUsers++;
                } else if (user.hash != null || user.masked != null) throw invalid();
                for (SocialRow social : socials(user.id)) {
                    if (social.raw != null) plainSocials++;
                    if (social.cipher != null) {
                        protection.decrypt(social.cipher, EmailProtection.SOCIAL);
                        if (social.raw != null) throw invalid();
                        encryptedSocials++;
                    }
                }
            }
            cursor = users.getLast().id;
        }
        return new Summary(plainUsers, plainSocials, encryptedUsers, encryptedSocials);
    }

    public Summary apply() {
        if (!protection.enabled()) throw new IllegalStateException("EMAIL_ENCRYPTED_WRITES_REQUIRED");
        check();
        long cursor = 0;
        while (true) {
            final long after = cursor;
            Long last = transaction.execute(tx -> {
                List<UserRow> users = usersAfter(after, true);
                for (UserRow user : users) {
                    // Lock the parent first, matching OAuth and withdrawal. Commit at most 100 users at a time.
                    if (user.raw != null) {
                        String cipher = protection.encrypt(user.raw, EmailProtection.USER);
                        verifyRoundTrip(user.raw, cipher, EmailProtection.USER);
                        jdbc.update("UPDATE app_user SET email_ciphertext=?,email_lookup_hash=?,email_masked=?,email=NULL WHERE user_id=?",
                                cipher, protection.lookupHash(user.raw), EmailMask.mask(user.raw), user.id);
                    }
                    for (SocialRow social : socials(user.id)) {
                        if (social.raw == null) continue;
                        String cipher = protection.encrypt(social.raw, EmailProtection.SOCIAL);
                        verifyRoundTrip(social.raw, cipher, EmailProtection.SOCIAL);
                        jdbc.update("UPDATE user_social_account SET provider_email_ciphertext=?,provider_email=NULL WHERE social_account_id=?",
                                cipher, social.id);
                    }
                }
                return users.isEmpty() ? null : users.getLast().id;
            });
            if (last == null) break;
            cursor = last;
        }
        Summary result = check();
        if (result.legacyUsers != 0 || result.legacySocials != 0) throw invalid();
        return result;
    }

    private List<UserRow> usersAfter(long id, boolean lock) {
        return jdbc.query("SELECT user_id,email,email_ciphertext,email_lookup_hash,email_masked FROM app_user WHERE user_id>? ORDER BY user_id LIMIT 100"
                + (lock ? " FOR UPDATE" : ""), (rs, n) -> new UserRow(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)), id);
    }
    private List<SocialRow> socials(long userId) {
        return jdbc.query("SELECT social_account_id,provider_email,provider_email_ciphertext FROM user_social_account WHERE user_id=? ORDER BY social_account_id",
                (rs, n) -> new SocialRow(rs.getLong(1),rs.getString(2),rs.getString(3)), userId);
    }
    private void verifyRoundTrip(String raw, String cipher, String purpose) {
        if (!Objects.equals(raw.isBlank() ? null : raw, protection.decrypt(cipher, purpose))) throw invalid();
    }
    private static IllegalStateException invalid() { return new IllegalStateException("EMAIL_MIGRATION_VERIFICATION_FAILED"); }

    public static void main(String[] args) {
        try {
            if (args.length != 1 || !(args[0].equals("--check") || args[0].equals("--apply")))
                throw new IllegalArgumentException("Use --check or --apply");
            var env = new StandardEnvironment();
            boolean apply = args[0].equals("--apply");
            if (apply && !"ENCRYPT_EXISTING_EMAILS".equals(env.getProperty("AUTH_EMAIL_MIGRATION_ACK")))
                throw new IllegalStateException("EMAIL_MIGRATION_ACK_REQUIRED");
            var ds = new DriverManagerDataSource(env.getRequiredProperty("SPRING_DB_URL"),
                    env.getRequiredProperty("SPRING_DB_USERNAME"), env.getRequiredProperty("SPRING_DB_PASSWORD"));
            var migration = new EmailPrivacyMigration(new JdbcTemplate(ds),
                    new TransactionTemplate(new DataSourceTransactionManager(ds)), new EmailProtection(env));
            System.out.println(apply ? migration.apply() : migration.check());
        } catch (Exception failure) {
            // JDBC exceptions can contain bound SQL values or connection details. Emit a fixed code only.
            System.err.println("EMAIL_PRIVACY_CHECK_FAILED");
            System.exit(1);
        }
    }
}
