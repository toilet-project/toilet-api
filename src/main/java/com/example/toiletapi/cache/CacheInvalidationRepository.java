package com.example.toiletapi.cache;

import java.util.List;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import com.example.toiletapi.cache.CacheInvalidationEventV3.RegionBounds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CacheInvalidationRepository {
    public record Pending(long toiletId, String eventId, long revision, CacheInvalidationEvent.Action action,
                          boolean catalogChanged, int attempts, boolean regionScopeComplete, RegionBounds regionBounds) {
        public Pending(long toiletId, String eventId, long revision, CacheInvalidationEvent.Action action,
                       boolean catalogChanged, int attempts) {
            this(toiletId,eventId,revision,action,catalogChanged,attempts,false,null);
        }
        CacheInvalidationEvent event() { return new CacheInvalidationEvent(toiletId, revision, action, catalogChanged); }
        CacheInvalidationEventV3 eventV3() {
            return new CacheInvalidationEventV3(toiletId,revision,action,catalogChanged,regionScopeComplete,regionBounds);
        }
    }
    private final JdbcTemplate jdbc;
    public CacheInvalidationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Pending> due() {
        return jdbc.query("SELECT toilet_id,event_id,revision,action,catalog_changed,attempts FROM web_cache_invalidation "
                +"WHERE delivered_at IS NULL AND next_attempt_at<=UTC_TIMESTAMP(6) "
                +"ORDER BY next_attempt_at,toilet_id LIMIT 100",
                (rs,n) -> new Pending(rs.getLong(1),rs.getString(2),rs.getLong(3),
                        CacheInvalidationEvent.Action.valueOf(rs.getString(4)),rs.getBoolean(5),rs.getInt(6)));
    }
    /** Only the opt-in v3 sender requires the V6 region-scope columns. */
    public List<Pending> dueScoped() {
        return jdbc.query("SELECT q.toilet_id,q.event_id,q.revision,q.action,q.catalog_changed,q.attempts,"
                +"q.region_scope_complete,q.region_west,q.region_south,q.region_east,q.region_north,"
                +"t.latitude AS current_latitude,t.longitude AS current_longitude "
                +"FROM web_cache_invalidation q LEFT JOIN toilet t ON t.toilet_id=q.toilet_id "
                +"WHERE q.delivered_at IS NULL AND q.next_attempt_at<=UTC_TIMESTAMP(6) "
                +"ORDER BY q.next_attempt_at,q.toilet_id LIMIT 100", (rs,n) -> pending(rs));
    }
    private static Double decimal(ResultSet rs, String column) throws SQLException {
        BigDecimal value=rs.getBigDecimal(column);
        return value==null ? null : value.doubleValue();
    }
    private static Pending pending(ResultSet rs) throws SQLException {
        Double west=decimal(rs,"region_west"), south=decimal(rs,"region_south");
        Double east=decimal(rs,"region_east"), north=decimal(rs,"region_north");
        boolean complete=rs.getBoolean("region_scope_complete");
        RegionBounds bounds=null;
        if (west!=null || south!=null || east!=null || north!=null) {
            if (west==null || south==null || east==null || north==null) complete=false;
            else try { bounds=new RegionBounds(west,south,east,north); }
            catch (IllegalArgumentException invalid) { complete=false; }
        }
        Double latitude=decimal(rs,"current_latitude"), longitude=decimal(rs,"current_longitude");
        if (latitude!=null || longitude!=null) {
            if (latitude==null || longitude==null) complete=false;
            else try { bounds=bounds==null ? RegionBounds.point(longitude,latitude) : bounds.include(longitude,latitude); }
            catch (IllegalArgumentException invalid) { complete=false; }
        }
        return new Pending(rs.getLong("toilet_id"),rs.getString("event_id"),rs.getLong("revision"),
                CacheInvalidationEvent.Action.valueOf(rs.getString("action")),rs.getBoolean("catalog_changed"),
                rs.getInt("attempts"),complete,bounds);
    }
    public long pendingCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM web_cache_invalidation WHERE delivered_at IS NULL", Long.class); }
    public long oldestPendingSeconds() {
        return jdbc.queryForObject("SELECT COALESCE(GREATEST(0,TIMESTAMPDIFF(SECOND,MIN(first_queued_at),UTC_TIMESTAMP(6))),0) FROM web_cache_invalidation WHERE delivered_at IS NULL", Long.class);
    }
    public void acknowledge(Pending item) {
        jdbc.update("UPDATE web_cache_invalidation SET delivered_at=UTC_TIMESTAMP(6),last_error_code=NULL "
                +"WHERE toilet_id=? AND event_id=? AND delivered_at IS NULL",item.toiletId(),item.eventId());
    }
    public void acknowledgeScoped(Pending item) {
        jdbc.update("UPDATE web_cache_invalidation SET delivered_at=UTC_TIMESTAMP(6),last_error_code=NULL,"
                +"region_west=NULL,region_south=NULL,region_east=NULL,region_north=NULL "
                +"WHERE toilet_id=? AND event_id=? AND delivered_at IS NULL",item.toiletId(),item.eventId());
    }
    public void retry(Pending item, String code) {
        int seconds = retrySeconds(item.attempts());
        jdbc.update("UPDATE web_cache_invalidation SET attempts=attempts+1,next_attempt_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),last_error_code=? WHERE toilet_id=? AND event_id=?",
                seconds,code,item.toiletId(),item.eventId());
    }
    static int retrySeconds(int attempts) { return (int)Math.min(3600, 10L << Math.min(Math.max(attempts,0),9)); }
}
