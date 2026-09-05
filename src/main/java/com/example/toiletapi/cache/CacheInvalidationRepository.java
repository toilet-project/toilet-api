package com.example.toiletapi.cache;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CacheInvalidationRepository {
    public record Pending(long toiletId, String eventId, int attempts) {}
    private final JdbcTemplate jdbc;
    public CacheInvalidationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Pending> due() {
        return jdbc.query("SELECT toilet_id,event_id,attempts FROM web_cache_invalidation WHERE next_attempt_at<=UTC_TIMESTAMP(6) ORDER BY next_attempt_at,toilet_id LIMIT 100",
                (rs,n) -> new Pending(rs.getLong(1),rs.getString(2),rs.getInt(3)));
    }
    public long pendingCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM web_cache_invalidation", Long.class); }
    public void acknowledge(Pending item) {
        jdbc.update("DELETE FROM web_cache_invalidation WHERE toilet_id=? AND event_id=?",item.toiletId(),item.eventId());
    }
    public void retry(Pending item, String code) {
        int seconds = retrySeconds(item.attempts());
        jdbc.update("UPDATE web_cache_invalidation SET attempts=attempts+1,next_attempt_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),last_error_code=? WHERE toilet_id=? AND event_id=?",
                seconds,code,item.toiletId(),item.eventId());
    }
    static int retrySeconds(int attempts) { return (int)Math.min(3600, 10L << Math.min(Math.max(attempts,0),9)); }
}
