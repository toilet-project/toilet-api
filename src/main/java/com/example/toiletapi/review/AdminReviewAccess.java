package com.example.toiletapi.review;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** An ADMIN claim alone cannot keep a withdrawn session or revoked role alive. */
@Component
public class AdminReviewAccess {
    private final JdbcTemplate jdbc;
    public AdminReviewAccess(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public long requireAdmin(Jwt jwt) {return requireAdmin(jwt,false);}
    /** Called inside the removal transaction; account suspension and role revocation must wait. */
    long requireAdminForMutation(Jwt jwt) {return requireAdmin(jwt,true);}
    private long requireAdmin(Jwt jwt,boolean lock) {
        final long userId, version;
        try {
            userId=Long.parseLong(jwt.getSubject());
            Number claim=jwt.getClaim("auth_version");
            version=claim==null?0:claim.longValue();
            if(userId<=0 || version<0)throw new IllegalArgumentException();
        } catch(RuntimeException invalid) { throw unauthorized(); }
        var accounts=jdbc.query("SELECT status,auth_version FROM app_user WHERE user_id=?"+(lock?" FOR UPDATE":""),
                (rs,n)->new Account(rs.getString(1),rs.getLong(2)),userId);
        if(accounts.size()!=1 || !"ACTIVE".equals(accounts.getFirst().status())
                || accounts.getFirst().version()!=version)throw unauthorized();
        var roles=jdbc.query("SELECT role FROM user_role WHERE user_id=? AND role='ADMIN'"+(lock?" FOR UPDATE":""),(rs,n)->rs.getString(1),userId);
        if(roles.isEmpty())throw new ReviewFailure(403,"ACCESS_DENIED","관리자 권한이 필요합니다.");
        return userId;
    }
    private static ReviewFailure unauthorized() {
        return new ReviewFailure(401,"AUTHENTICATION_REQUIRED","로그인을 다시 확인해 주세요.");
    }
    private record Account(String status,long version) { }
}
