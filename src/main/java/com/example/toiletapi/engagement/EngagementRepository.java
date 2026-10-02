package com.example.toiletapi.engagement;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EngagementRepository {
    private final JdbcTemplate jdbc;
    public EngagementRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    record Account(String status,long version) { }
    boolean visible(long toilet) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM toilet WHERE toilet_id=? AND visibility_status='VISIBLE')",Boolean.class,toilet)); }
    Optional<Account> lockUser(long id) { return jdbc.query("SELECT status,auth_version FROM app_user WHERE user_id=? FOR UPDATE",(r,n)->new Account(r.getString(1),r.getLong(2)),id).stream().findFirst(); }
    Optional<Account> user(long id) { return jdbc.query("SELECT status,auth_version FROM app_user WHERE user_id=?",(r,n)->new Account(r.getString(1),r.getLong(2)),id).stream().findFirst(); }
    EngagementService.Counts counts(long toilet) {
        return new EngagementService.Counts(toilet,
                jdbc.queryForObject("SELECT COALESCE((SELECT total_views FROM toilet_view_stats WHERE toilet_id=?),0)",Long.class,toilet),
                jdbc.queryForObject("SELECT COUNT(*) FROM toilet_like l JOIN app_user u ON u.user_id=l.user_id AND u.status='ACTIVE' WHERE l.toilet_id=?",Long.class,toilet));
    }
    LocalDateTime lockGuard(String session,long toilet,LocalDateTime expiry) {
        jdbc.update("INSERT INTO toilet_view_guard(session_hash,toilet_id,expires_at) VALUES(?,?,?) ON DUPLICATE KEY UPDATE expires_at=VALUES(expires_at)",session,toilet,expiry);
        return jdbc.queryForObject("SELECT last_counted_at FROM toilet_view_guard WHERE session_hash=? AND toilet_id=? FOR UPDATE",(r,n)->r.getObject(1,LocalDateTime.class),session,toilet);
    }
    record Receipt(long toilet,boolean processed) { }
    Receipt lockReceipt(String event,long toilet,LocalDateTime expiry) {
        // An upsert first avoids both repeatable-read stale snapshots and missing-row gap-lock races.
        jdbc.update("INSERT INTO toilet_view_receipt(event_hash,toilet_id,expires_at) VALUES(?,?,?) ON DUPLICATE KEY UPDATE event_hash=VALUES(event_hash)",event,toilet,expiry);
        return jdbc.queryForObject("SELECT toilet_id,processed FROM toilet_view_receipt WHERE event_hash=? FOR UPDATE",(r,n)->new Receipt(r.getLong(1),r.getBoolean(2)),event);
    }
    void remember(String event) { jdbc.update("UPDATE toilet_view_receipt SET processed=TRUE WHERE event_hash=?",event); }
    void increment(String session,long toilet,LocalDateTime now) {
        jdbc.update("INSERT INTO toilet_view_stats(toilet_id,total_views,first_view_at,last_view_at) VALUES(?,1,?,?) ON DUPLICATE KEY UPDATE total_views=total_views+1,last_view_at=VALUES(last_view_at)",toilet,now,now);
        jdbc.update("INSERT INTO toilet_view_daily(view_date,toilet_id,views) VALUES(?,?,1) ON DUPLICATE KEY UPDATE views=views+1",now.toLocalDate(),toilet);
        jdbc.update("UPDATE toilet_view_guard SET last_counted_at=? WHERE session_hash=? AND toilet_id=?",now,session,toilet);
    }
    boolean liked(long user,long toilet) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM toilet_like WHERE user_id=? AND toilet_id=?)",Boolean.class,user,toilet)); }
    record LikedToilet(long id,String name,String toiletType,BigDecimal latitude,BigDecimal longitude,LocalDateTime likedAt) { }
    long likedCount(long user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM toilet_like l JOIN toilet t ON t.toilet_id=l.toilet_id AND t.visibility_status='VISIBLE' WHERE l.user_id=?",Long.class,user);
    }
    List<LikedToilet> likedToilets(long user,String sort,int size,int offset,Double latitude,Double longitude) {
        String order=switch(sort) {
            case "oldest" -> "l.created_at ASC,l.toilet_id ASC";
            case "distance" -> "CASE WHEN t.latitude IS NULL OR t.longitude IS NULL THEN 1 ELSE 0 END ASC,"
                    + "POWER((t.latitude-?)*111320,2)+POWER((t.longitude-?)*111320*COS(RADIANS(?)),2) ASC,"
                    + "l.created_at DESC,l.toilet_id DESC";
            default -> "l.created_at DESC,l.toilet_id DESC";
        };
        String sql="SELECT l.toilet_id,t.name,t.toilet_type,t.latitude,t.longitude,l.created_at "
                + "FROM toilet_like l JOIN toilet t ON t.toilet_id=l.toilet_id AND t.visibility_status='VISIBLE' "
                + "WHERE l.user_id=? ORDER BY "+order+" LIMIT ? OFFSET ?";
        Object[] args="distance".equals(sort)
                ? new Object[] {user,latitude,longitude,latitude,size,offset}
                : new Object[] {user,size,offset};
        return jdbc.query(sql,(r,n)->new LikedToilet(r.getLong("toilet_id"),r.getString("name"),r.getString("toilet_type"),
                r.getBigDecimal("latitude"),r.getBigDecimal("longitude"),r.getObject("created_at",LocalDateTime.class)),args);
    }
    void like(long user,long toilet,LocalDateTime now) { jdbc.update("INSERT INTO toilet_like(user_id,toilet_id,created_at) VALUES(?,?,?) ON DUPLICATE KEY UPDATE toilet_id=VALUES(toilet_id)",user,toilet,now); }
    void unlike(long user,long toilet) { jdbc.update("DELETE FROM toilet_like WHERE user_id=? AND toilet_id=?",user,toilet); }
    public void removeUserLikes(long user) { jdbc.update("DELETE FROM toilet_like WHERE user_id=?",user); }
    public void cleanup(LocalDateTime now) {
        jdbc.update("DELETE FROM toilet_view_receipt WHERE expires_at<? LIMIT 5000",now);
        jdbc.update("DELETE FROM toilet_view_guard WHERE expires_at<? LIMIT 5000",now);
    }
}
