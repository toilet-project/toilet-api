package com.example.toiletapi.cache;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CacheInvalidationRepository {
    public record Pending(long toiletId, String eventId, long revision, CacheInvalidationEvent.Action action,
                          boolean catalogChanged, int attempts) {
        CacheInvalidationEvent event() { return new CacheInvalidationEvent(toiletId, revision, action, catalogChanged); }
    }
    private final JdbcTemplate jdbc;
    public CacheInvalidationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Pending> due() {
        return jdbc.query("SELECT toilet_id,event_id,revision,action,catalog_changed,attempts FROM web_cache_invalidation WHERE delivered_at IS NULL AND next_attempt_at<=UTC_TIMESTAMP(6) ORDER BY next_attempt_at,toilet_id LIMIT 100",
                (rs,n) -> new Pending(rs.getLong(1),rs.getString(2),rs.getLong(3),CacheInvalidationEvent.Action.valueOf(rs.getString(4)),rs.getBoolean(5),rs.getInt(6)));
    }
    public long pendingCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM web_cache_invalidation WHERE delivered_at IS NULL", Long.class); }
    public long oldestPendingSeconds() {
        return jdbc.queryForObject("SELECT COALESCE(GREATEST(0,TIMESTAMPDIFF(SECOND,MIN(first_queued_at),UTC_TIMESTAMP(6))),0) FROM web_cache_invalidation WHERE delivered_at IS NULL", Long.class);
    }
    public void acknowledge(Pending item) {
        jdbc.update("UPDATE web_cache_invalidation SET delivered_at=UTC_TIMESTAMP(6),last_error_code=NULL WHERE toilet_id=? AND event_id=? AND delivered_at IS NULL",item.toiletId(),item.eventId());
    }
    public void retry(Pending item, String code) {
        int seconds = retrySeconds(item.attempts());
        jdbc.update("UPDATE web_cache_invalidation SET attempts=attempts+1,next_attempt_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),last_error_code=? WHERE toilet_id=? AND event_id=?",
                seconds,code,item.toiletId(),item.eventId());
    }
    static int retrySeconds(int attempts) { return (int)Math.min(3600, 10L << Math.min(Math.max(attempts,0),9)); }
}
