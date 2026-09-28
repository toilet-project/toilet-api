package com.example.toiletapi.auth.privacy;

import static org.assertj.core.api.Assertions.*;
import com.example.toiletapi.auth.model.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class EmailProtectionTest {
    static MockEnvironment settings() {
        // Synthetic test material only.
        return new MockEnvironment().withProperty("AUTH_EMAIL_ENCRYPTION_ENABLED", "true")
                .withProperty("AUTH_EMAIL_ACTIVE_KEY_ID", "V1")
                .withProperty("AUTH_EMAIL_KEY_V1", Base64.getEncoder().encodeToString(new byte[32]))
                .withProperty("AUTH_EMAIL_SEARCH_KEY", Base64.getEncoder().encodeToString("12345678901234567890123456789012".getBytes()));
    }
    @Test void masksShortUnicodeInvalidAndAlreadyMaskedAddresses() {
        assertThat(EmailMask.mask("hello@example.test")).isEqualTo("he***@example.test");
        assertThat(EmailMask.mask("ab@example.test")).isEqualTo("a***@example.test");
        assertThat(EmailMask.mask("a@example.test")).isEqualTo("***@example.test");
        assertThat(EmailMask.mask("가나다@example.test")).isEqualTo("가나***@example.test");
        assertThat(EmailMask.mask("he***@example.test")).isEqualTo("he***@example.test");
        assertThat(EmailMask.mask("bad@email@other.test")).isEqualTo("***");
        assertThat(EmailMask.mask("bad\n@example.test")).isEqualTo("***");
        assertThat(EmailMask.mask(null)).isNull();
    }
    @Test void randomNonceAndAuthenticatedCipherRejectTamperingAndWrongField() {
        var protection = new EmailProtection(settings());
        String encrypted = protection.encrypt("private@example.test", EmailProtection.USER);
        assertThat(encrypted).doesNotContain("private").startsWith("e1.V1.");
        assertThat(protection.encrypt("private@example.test", EmailProtection.USER)).isNotEqualTo(encrypted);
        assertThat(protection.decrypt(encrypted, EmailProtection.USER)).isEqualTo("private@example.test");
        assertThatThrownBy(() -> protection.decrypt(encrypted, EmailProtection.SOCIAL)).hasMessage("EMAIL_PROTECTION_UNAVAILABLE");
        char replacement = encrypted.charAt(10) == 'A' ? 'B' : 'A';
        String tampered = encrypted.substring(0,10) + replacement + encrypted.substring(11);
        assertThatThrownBy(() -> protection.decrypt(tampered, EmailProtection.USER)).hasMessage("EMAIL_PROTECTION_UNAVAILABLE");
    }
    @Test void validatesKeysBeforeEncryptedWritesAndCanReadPreviousKey() {
        assertThatThrownBy(() -> new EmailProtection(new MockEnvironment().withProperty("AUTH_EMAIL_ENCRYPTION_ENABLED","true")))
                .hasMessage("EMAIL_PROTECTION_UNAVAILABLE");
        var old = new EmailProtection(settings());
        String encrypted = old.encrypt("old@example.test", EmailProtection.USER);
        var env = settings().withProperty("AUTH_EMAIL_ACTIVE_KEY_ID","V2")
                .withProperty("AUTH_EMAIL_KEY_V2", Base64.getEncoder().encodeToString("abcdefghijklmnopqrstuvwxyz123456".getBytes()));
        assertThat(new EmailProtection(env).decrypt(encrypted, EmailProtection.USER)).isEqualTo("old@example.test");
        env = settings().withProperty("AUTH_EMAIL_KEY_V1", Base64.getEncoder().encodeToString(new byte[16]));
        final var invalid = env;
        assertThatThrownBy(() -> new EmailProtection(invalid)).hasMessage("EMAIL_PROTECTION_UNAVAILABLE");
    }
    @Test void protectedProfileRefreshSearchAndWithdrawalPreserveIdentity() {
        var protection = new EmailProtection(settings());
        var user = AppUser.create("nickname","old@example.test",true);
        var social = UserSocialAccount.link(user, SocialProvider.GOOGLE, "a".repeat(64), "old@example.test");
        protection.protect(user); protection.protect(social);
        assertThat(user.getEmail()).isNull();
        assertThat(social.getProviderEmail()).isNull();
        assertThat(protection.email(user)).isEqualTo("old@example.test");
        assertThat(user.getMaskedEmail()).isEqualTo("ol***@example.test");
        assertThat(protection.lookupHash(" OLD@EXAMPLE.TEST ")).isEqualTo(user.getEmailLookupHash());
        user.refreshOAuthProfile("provider name","new@example.test",true);
        social.recordLogin("new@example.test");
        protection.protect(user); protection.protect(social);
        assertThat(protection.email(user)).isEqualTo("new@example.test");
        assertThat(user.getDisplayName()).isEqualTo("nickname");
        assertThat(social.getProviderSubjectHash()).isEqualTo("a".repeat(64));
        user.withdraw(); social.clearPersonalProfile();
        assertThat(user.getEmailCiphertext()).isNull();
        assertThat(user.getEmailLookupHash()).isNull();
        assertThat(user.getMaskedEmail()).isNull();
        assertThat(social.getProviderEmailCiphertext()).isNull();
    }
    @Test void compatibilityModeDoesNotDowngradeProtectedRows() {
        var enabled = new EmailProtection(settings());
        var user = AppUser.create("name","person@example.test",true);
        enabled.protect(user);
        var compatible = new EmailProtection(settings().withProperty("AUTH_EMAIL_ENCRYPTION_ENABLED","false"));
        user.refreshOAuthProfile(null,"changed@example.test",true);
        compatible.protect(user);
        assertThat(user.getEmail()).isNull();
        assertThat(compatible.email(user)).isEqualTo("changed@example.test");
    }
}
