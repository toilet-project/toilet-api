package com.example.toiletapi.auth.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 최초 관리자 역할을 부여할, 이메일 인증을 완료한 운영자 계정 목록입니다.
 *
 * <p>이 값은 코드나 DB migration에 넣지 않고 운영 환경변수로만 관리합니다. 한 번 ADMIN 역할이
 * 저장된 계정은 이후 allow-list에서 제거해도 역할이 자동으로 회수되지 않습니다.</p>
 */
@ConfigurationProperties(prefix = "auth.admin-bootstrap")
public record AdminBootstrapProperties(String emails) {

    public boolean isBootstrapAdmin(String email, boolean emailVerified) {
        if (!emailVerified || email == null || email.isBlank()) {
            return false;
        }

        String normalizedEmail = normalize(email);
        return Arrays.stream(emails == null ? new String[0] : emails.split(","))
                .map(AdminBootstrapProperties::normalize)
                .anyMatch(normalizedEmail::equals);
    }

    public Set<String> configuredEmails() {
        return Arrays.stream(emails == null ? new String[0] : emails.split(","))
                .map(AdminBootstrapProperties::normalize)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
