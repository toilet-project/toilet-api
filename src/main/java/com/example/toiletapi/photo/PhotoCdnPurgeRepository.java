package com.example.toiletapi.photo;

import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PhotoCdnPurgeRepository {
    record Pending(String version,int attempts) { }
    private final JdbcTemplate jdbc;
    public PhotoCdnPurgeRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    void queueKey(String key) {
        if(key==null || !key.matches("avatars/[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}\\.webp")) return;
        String version=key.substring(8,key.length()-5);
        var now=com.example.toiletapi.global.time.KoreanTime.now();
        if(jdbc.update("UPDATE profile_photo_cdn_purge SET next_attempt_at=?,last_error_code=NULL WHERE photo_version=?",now,version)>0) return;
        try {jdbc.update("INSERT INTO profile_photo_cdn_purge(photo_version,first_queued_at,next_attempt_at) VALUES(?,?,?)",version,now,now);}
        catch(DuplicateKeyException race) {jdbc.update("UPDATE profile_photo_cdn_purge SET next_attempt_at=?,last_error_code=NULL WHERE photo_version=?",now,version);}
    }
    List<Pending> due(){return jdbc.query("SELECT photo_version,attempts FROM profile_photo_cdn_purge WHERE next_attempt_at<=? ORDER BY next_attempt_at,photo_version LIMIT 30",
            (rs,n)->new Pending(rs.getString(1),rs.getInt(2)),com.example.toiletapi.global.time.KoreanTime.now());}
    void acknowledge(Pending item){jdbc.update("DELETE FROM profile_photo_cdn_purge WHERE photo_version=?",item.version());}
    void retry(Pending item,String code){jdbc.update("UPDATE profile_photo_cdn_purge SET attempts=attempts+1,next_attempt_at=?,last_error_code=? WHERE photo_version=?",
            com.example.toiletapi.global.time.KoreanTime.now().plusSeconds(retrySeconds(item.attempts())),code,item.version());}
    long pendingCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM profile_photo_cdn_purge",Long.class);}
    static int retrySeconds(int attempts){return (int)Math.min(3600,2L<<Math.min(Math.max(attempts,0),10));}
}
