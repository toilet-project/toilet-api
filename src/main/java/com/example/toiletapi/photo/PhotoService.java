package com.example.toiletapi.photo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class PhotoService {
    private final PhotoSettings settings;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PhotoStore store;
    public PhotoService(PhotoSettings settings, JdbcTemplate jdbc, PlatformTransactionManager transactions, PhotoStore store) {
        this.settings=settings;this.jdbc=jdbc;this.tx=new TransactionTemplate(transactions);this.store=store;
    }
    public record State(boolean available, boolean useSocial, boolean publicPhoto, String imageVersion) { }
    public record Ticket(long userId,long authVersion,long generation) { }
    record Row(boolean useSocial,boolean publicPhoto,long generation,String key,String hash,String source,LocalDateTime checked) { }
    private LocalDateTime now() { return com.example.toiletapi.global.time.KoreanTime.now(); }
    private Row row(long user) {
        return jdbc.query("SELECT * FROM profile_photo WHERE user_id=?",(rs,n)->new Row(rs.getBoolean("use_social"),rs.getBoolean("is_public"),
                rs.getLong("generation"),rs.getString("object_key"),rs.getString("content_hash"),rs.getString("source_hash"),rs.getObject("checked_at",LocalDateTime.class)),user)
                .stream().findFirst().orElse(new Row(false,false,0,null,null,null,null));
    }
    private long active(long user,boolean lock) {
        var users=jdbc.query("SELECT status,auth_version FROM app_user WHERE user_id=?"+(lock?" FOR UPDATE":""),
                (rs,n)->new Object[]{rs.getString(1),rs.getLong(2)},user);
        if(users.isEmpty() || !"ACTIVE".equals(users.getFirst()[0])) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return (Long)users.getFirst()[1];
    }
    public State state(long user) {
        if(!settings.enabled()) return new State(false,false,false,null);
        active(user,false);
        Row r=row(user);
        return new State(true,r.useSocial,r.publicPhoto,r.key==null?null:r.key.substring(8,44));
    }
    public State update(long user,boolean useSocial,boolean publicPhoto) {
        if(!settings.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        if(publicPhoto && !useSocial) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return tx.execute(status->{
            active(user,true);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM profile_photo WHERE user_id=?",Integer.class,user)==0)
                jdbc.update("INSERT INTO profile_photo(user_id,updated_at) VALUES(?,?)",user,now());
            Row before=row(user);
            // Increment on every preference save so older in-flight work cannot undo a decision.
            jdbc.update("UPDATE profile_photo SET use_social=?,is_public=?,generation=generation+1,updated_at=? WHERE user_id=?",useSocial,publicPhoto,now(),user);
            if(!useSocial) clear(user);
            else if(!before.useSocial) jdbc.update("UPDATE profile_photo SET checked_at=NULL,source_hash=NULL WHERE user_id=?",user);
            return state(user);
        });
    }
    /** Called inside the same app_user-locked withdrawal transaction. Photo is never retained for recovery. */
    public void withdraw(long user) {
        if(!settings.enabled()) return;
        // app_user.auth_version changes on withdrawal; old jobs cannot attach after recovery.
        jdbc.update("DELETE FROM profile_photo WHERE user_id=?",user);
    }
    private void clear(long user) {
        jdbc.update("UPDATE profile_photo SET object_key=NULL,content_hash=NULL,source_hash=NULL,checked_at=?,updated_at=? WHERE user_id=?",now(),now(),user);
    }
    public Ticket ticket(long user,String sourceHash) {
        if(!settings.enabled()) return null;
        return tx.execute(status->{
            long version=active(user,true); Row r=row(user);
            if(!r.useSocial || (Objects.equals(r.source,sourceHash) && r.checked!=null && r.checked.isAfter(now().minusHours(24)))) return null;
            return new Ticket(user,version,r.generation);
        });
    }
    private boolean valid(Ticket ticket) {
        try { return active(ticket.userId,true)==ticket.authVersion && row(ticket.userId).useSocial && row(ticket.userId).generation==ticket.generation; }
        catch(ResponseStatusException e) { return false; }
    }
    public void absent(Ticket ticket) {
        tx.executeWithoutResult(status->{if(valid(ticket)) { clear(ticket.userId);jdbc.update("UPDATE profile_photo SET source_hash='absent' WHERE user_id=?",ticket.userId); }});
    }
    public boolean unchanged(Ticket ticket,String hash,String source) {
        return Boolean.TRUE.equals(tx.execute(status->{
            if(!valid(ticket)) return true;
            if(!Objects.equals(row(ticket.userId).hash,hash)) return false;
            jdbc.update("UPDATE profile_photo SET source_hash=?,checked_at=? WHERE user_id=?",source,now(),ticket.userId);return true;
        }));
    }
    public void save(Ticket ticket,byte[] bytes,String hash,String source) {
        String key="avatars/"+java.util.UUID.randomUUID()+".webp";
        tx.executeWithoutResult(status->jdbc.update("INSERT INTO profile_photo_object(object_key,created_at) VALUES(?,?)",key,now()));
        // A failed/ambiguous PUT remains in the object journal and is safely retried by cleanup.
        store.put(key,bytes);
        tx.executeWithoutResult(status->{
            if(!valid(ticket)) return;
            var objects=jdbc.query("SELECT created_at FROM profile_photo_object WHERE object_key=? FOR UPDATE",(rs,n)->rs.getObject(1,LocalDateTime.class),key);
            if(objects.isEmpty() || objects.getFirst().isBefore(now().minusMinutes(2))) return;
            jdbc.update("UPDATE profile_photo SET object_key=?,content_hash=?,source_hash=?,checked_at=?,updated_at=? WHERE user_id=?",
                    key,hash,source,now(),now(),ticket.userId);
        });
    }
    private String ownKey(long user,String version) {
        active(user,false); Row r=row(user);
        return r.useSocial && r.key!=null && Objects.equals(r.key,"avatars/"+version+".webp")?r.key:null;
    }
    public byte[] ownImage(long user,String version) {
        return read(()->ownKey(user,version));
    }
    private String reviewKey(long toilet,long review) {
        return jdbc.query("""
                SELECT p.object_key FROM toilet_review r JOIN app_user u ON u.user_id=r.author_user_id
                JOIN profile_photo p ON p.user_id=u.user_id
                WHERE r.toilet_id=? AND r.review_id=? AND r.author_detached=FALSE AND u.status='ACTIVE'
                AND p.use_social=TRUE AND p.is_public=TRUE AND p.object_key IS NOT NULL
                """,(rs,n)->rs.getString(1),toilet,review).stream().findFirst().orElse(null);
    }
    public byte[] reviewImage(long toilet,long review) { return read(()->reviewKey(toilet,review)); }
    private byte[] read(java.util.function.Supplier<String> authorizedKey) {
        if(!settings.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String key=authorizedKey.get();
        if(key==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        byte[] image;
        try { image=store.get(key); } catch(Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE); }
        // Re-check a visibility change / unlink / withdrawal that committed during the storage read.
        if(!key.equals(authorizedKey.get())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return image;
    }
    public void cleanup() {
        if(!settings.enabled()) return;
        // Catch deletions by the batch/restore path too; the FK leaves an ownerless object journal.
        List<Long> inactive=jdbc.query("SELECT p.user_id FROM profile_photo p JOIN app_user u ON u.user_id=p.user_id WHERE u.status='WITHDRAWN' LIMIT 50",(rs,n)->rs.getLong(1));
        for(long user:inactive) tx.executeWithoutResult(status->{
            var locked=jdbc.query("SELECT status FROM app_user WHERE user_id=? FOR UPDATE",(rs,n)->rs.getString(1),user);
            if(!locked.isEmpty() && "WITHDRAWN".equals(locked.getFirst())) withdraw(user);
        });
        List<String> candidates=jdbc.query("SELECT object_key FROM profile_photo_object WHERE created_at<? AND NOT EXISTS (SELECT 1 FROM profile_photo p WHERE p.object_key=profile_photo_object.object_key) ORDER BY created_at LIMIT 5",(rs,n)->rs.getString(1),now().minusMinutes(5));
        for(String key:candidates) {
            try { tx.executeWithoutResult(status->{
                if(jdbc.query("SELECT object_key FROM profile_photo_object WHERE object_key=? FOR UPDATE",(rs,n)->rs.getString(1),key).isEmpty()) return;
                if(jdbc.queryForObject("SELECT COUNT(*) FROM profile_photo WHERE object_key=?",Integer.class,key)>0) return;
                store.delete(key);jdbc.update("DELETE FROM profile_photo_object WHERE object_key=?",key);
            }); } catch(Exception failure) { /* Keep journal entry for next retry; no sensitive details in logs. */ }
        }
    }
}
