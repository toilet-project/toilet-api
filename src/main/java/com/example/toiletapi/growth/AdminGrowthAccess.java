package com.example.toiletapi.growth;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Uses current account and role records, including when an old token still contains ADMIN. */
@Component
public class AdminGrowthAccess {
    private final JdbcTemplate jdbc;
    public AdminGrowthAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long requireAdmin(Jwt jwt) {
        final long userId, version;
        try {
            userId = Long.parseLong(jwt.getSubject());
            Number claim = jwt.getClaim("auth_version");
            version = claim == null ? 0 : claim.longValue();
            if (userId <= 0 || version < 0) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인을 다시 확인해 주세요.");
        }
        var accounts = jdbc.query("SELECT status,auth_version FROM app_user WHERE user_id=?",
                (rs, n) -> new Account(rs.getString(1), rs.getLong(2)), userId);
        if (accounts.size() != 1 || !"ACTIVE".equals(accounts.getFirst().status())
                || accounts.getFirst().version() != version) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "사용할 수 없는 로그인 세션입니다.");
        }
        Integer roles = jdbc.queryForObject("SELECT COUNT(*) FROM user_role WHERE user_id=? AND role='ADMIN'", Integer.class, userId);
        if (roles == null || roles == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        return userId;
    }

    public CandidatePage candidates(long afterUserId, int size) {
        if (afterUserId < 0 || size < 1 || size > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 기준은 0 이상, 조회 수는 1~50이어야 합니다.");
        }
        var ids = jdbc.query("SELECT user_id FROM app_user WHERE status='ACTIVE' AND user_id>? ORDER BY user_id LIMIT ?",
                (rs,n) -> Long.toString(rs.getLong(1)), afterUserId, size + 1);
        boolean more = ids.size() > size;
        var page = List.copyOf(ids.subList(0, Math.min(ids.size(), size)));
        return new CandidatePage(page, more ? page.getLast() : null, more);
    }

    private record Account(String status, long version) { }
    public record CandidatePage(List<String> userIds, String nextAfterUserId, boolean hasMore) { }
}
