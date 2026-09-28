package com.example.toiletapi.auth.privacy;

import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.model.UserSocialAccount;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Expand/read-compatible release first; enable encrypted writes only after every API instance is upgraded. */
@Component
public final class EmailProtection {
    public static final String USER = "app_user.email";
    public static final String SOCIAL = "user_social_account.provider_email";
    private final Environment env;
    private final boolean enabled;
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();

    public EmailProtection(Environment env) {
        this.env = env;
        this.enabled = env.getProperty("AUTH_EMAIL_ENCRYPTION_ENABLED", Boolean.class, false);
        this.activeKeyId = env.getProperty("AUTH_EMAIL_ACTIVE_KEY_ID", "V1");
        if (enabled) { key(activeKeyId); searchKey(); }
    }

    public boolean enabled() { return enabled; }

    public String email(AppUser user) {
        return user.getEmailCiphertext() == null ? user.getEmail() : decrypt(user.getEmailCiphertext(), USER);
    }

    public void protect(AppUser user) {
        // Never downgrade an already protected row when the rollout flag is accidentally turned off.
        if (!enabled && user.getEmailCiphertext() == null) return;
        String value = user.getEmail() != null ? user.getEmail() : email(user);
        user.protectEmail(encrypt(value, USER), lookupHash(value), EmailMask.mask(value));
    }

    public void protect(UserSocialAccount social) {
        if (!enabled && social.getProviderEmailCiphertext() == null) return;
        String value = social.getProviderEmail() != null ? social.getProviderEmail()
                : decrypt(social.getProviderEmailCiphertext(), SOCIAL);
        social.protectEmail(encrypt(value, SOCIAL));
    }

    public String lookupHash(String email) {
        if (email == null || email.isBlank()) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(searchKey(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw unavailable(); }
    }

    public String searchHash(String keyword) {
        if (keyword == null || !keyword.contains("@")) return null;
        // During the read-compatible release, keys may not have been provisioned yet.
        if (!enabled && env.getProperty("AUTH_EMAIL_SEARCH_KEY") == null) return null;
        return lookupHash(keyword);
    }

    public String encrypt(String value, String purpose) {
        if (value == null || value.isBlank()) return null;
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(activeKeyId), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(purpose));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] packed = Arrays.copyOf(nonce, nonce.length + ciphertext.length);
            System.arraycopy(ciphertext, 0, packed, nonce.length, ciphertext.length);
            return "e1." + activeKeyId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
        } catch (Exception failure) { throw unavailable(); }
    }

    public String decrypt(String value, String purpose) {
        if (value == null) return null;
        try {
            String[] pieces = value.split("\\.", -1);
            if (pieces.length != 3 || !"e1".equals(pieces[0])) throw unavailable();
            byte[] packed = Base64.getUrlDecoder().decode(pieces[2]);
            if (packed.length < 29) throw unavailable();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(pieces[1]), "AES"), new GCMParameterSpec(128, packed, 0, 12));
            cipher.updateAAD(aad(purpose));
            return new String(cipher.doFinal(packed, 12, packed.length - 12), StandardCharsets.UTF_8);
        } catch (Exception failure) { throw unavailable(); }
    }

    private byte[] key(String id) {
        if (id == null || !id.matches("[A-Z0-9_]{1,32}")) throw unavailable();
        return secret("AUTH_EMAIL_KEY_" + id);
    }
    private byte[] searchKey() { return secret("AUTH_EMAIL_SEARCH_KEY"); }
    private byte[] secret(String name) {
        try {
            byte[] decoded = Base64.getDecoder().decode(env.getRequiredProperty(name));
            if (decoded.length != 32) throw unavailable();
            return decoded;
        } catch (Exception failure) { throw unavailable(); }
    }
    private byte[] aad(String purpose) { return ("geupddong.email.e1:" + purpose).getBytes(StandardCharsets.UTF_8); }
    private static IllegalStateException unavailable() { return new IllegalStateException("EMAIL_PROTECTION_UNAVAILABLE"); }
}
