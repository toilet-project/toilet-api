package com.example.toiletapi.photo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PhotoService {
    public static final String NOTICE_VERSION = "profile-photo-us-2026-09-13";
    private final PhotoSettings settings;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PhotoStore store;

    public PhotoService(PhotoSettings settings, JdbcTemplate jdbc, PlatformTransactionManager transactions, PhotoStore store) {
        this.settings=settings;this.jdbc=jdbc;this.tx=new TransactionTemplate(transactions);this.store=store;
    }

    public record State(boolean available, boolean publicPhoto, String imageVersion) { }
    public record Ticket(long userId,long authVersion,long generation) { }
    record Row(boolean publicPhoto,long generation,String key,String hash) { }

    private LocalDateTime now() { return com.example.toiletapi.global.time.KoreanTime.now(); }
    private Row row(long user) {
        return jdbc.query("SELECT is_public,generation,object_key,content_hash FROM profile_photo WHERE user_id=?",
                (rs,n)->new Row(rs.getBoolean(1),rs.getLong(2),rs.getString(3),rs.getString(4)),user)
                .stream().findFirst().orElse(new Row(false,0,null,null));
    }
    private long active(long user,boolean lock) {
        var users=jdbc.query("SELECT status,auth_version FROM app_user WHERE user_id=?"+(lock?" FOR UPDATE":""),
                (rs,n)->new Object[]{rs.getString(1),rs.getLong(2)},user);
        if(users.isEmpty() || !"ACTIVE".equals(users.getFirst()[0])) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return (Long)users.getFirst()[1];
    }
    private void ensureRow(long user) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM profile_photo WHERE user_id=?",Integer.class,user)==0)
            jdbc.update("INSERT INTO profile_photo(user_id,updated_at) VALUES(?,?)",user,now());
    }
    public State state(long user) {
        if(!settings.enabled()) return new State(false,false,null);
        active(user,false);
        Row r=row(user);
        return new State(true,r.key!=null && r.publicPhoto,r.key==null?null:r.key.substring(8,44));
    }
    public State visibility(long user,boolean publicPhoto) {
        if(!settings.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return tx.execute(status->{
            active(user,true);Row current=row(user);
            if(current.key==null) throw new ResponseStatusException(HttpStatus.CONFLICT);
            jdbc.update("UPDATE profile_photo SET is_public=?,generation=generation+1,updated_at=? WHERE user_id=?",publicPhoto,now(),user);
            return state(user);
        });
    }
    public State delete(long user) {
        if(!settings.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return tx.execute(status->{
            active(user,true);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM profile_photo WHERE user_id=?",Integer.class,user)>0) clear(user);
            return state(user);
        });
    }
    /** Creates a generation that an older signup import or upload cannot overwrite. */
    public Ticket uploadTicket(long user) {
        if(!settings.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return tx.execute(status->{
            long version=active(user,true);ensureRow(user);
            jdbc.update("UPDATE profile_photo SET generation=generation+1,updated_at=? WHERE user_id=?",now(),user);
            return new Ticket(user,version,row(user).generation);
        });
    }
    /** Only a first activation (auth_version=0) with no previous photo decision can import. */
    public Ticket signupTicket(long user) {
        if(!settings.enabled()) return null;
        return tx.execute(status->{
            long version=active(user,true);
            if(version!=0 || jdbc.queryForObject("SELECT COUNT(*) FROM profile_photo WHERE user_id=?",Integer.class,user)!=0) return null;
            jdbc.update("INSERT INTO profile_photo(user_id,generation,updated_at) VALUES(?,1,?)",user,now());
            return new Ticket(user,version,1);
        });
    }
    private boolean valid(Ticket ticket) {
        try {return active(ticket.userId,true)==ticket.authVersion && row(ticket.userId).generation==ticket.generation;}
        catch(ResponseStatusException e) {return false;}
    }
    public boolean saveWithReceipt(Ticket ticket,byte[] bytes,String sourceKind,String noticeVersion,LocalDateTime collectedAt) throws Exception {
        if(!sourceKind.matches("KAKAO_SIGNUP|DIRECT_UPLOAD") || !NOTICE_VERSION.equals(noticeVersion) || collectedAt==null)
            throw new IllegalArgumentException("Invalid photo receipt");
        String hash=PhotoSync.hash(bytes),key="avatars/"+java.util.UUID.randomUUID()+".webp";
        tx.executeWithoutResult(status->jdbc.update("INSERT INTO profile_photo_object(object_key,created_at) VALUES(?,?)",key,now()));
        store.put(key,bytes);
        return Boolean.TRUE.equals(tx.execute(status->{
            if(!valid(ticket)) return false;
            var objects=jdbc.query("SELECT created_at FROM profile_photo_object WHERE object_key=? FOR UPDATE",
                    (rs,n)->rs.getObject(1,LocalDateTime.class),key);
            if(objects.isEmpty() || objects.getFirst().isBefore(now().minusMinutes(2))) return false;
            jdbc.update("UPDATE profile_photo SET object_key=?,content_hash=?,source_kind=?,notice_version=?,collected_at=?,updated_at=? WHERE user_id=?",
                    key,hash,sourceKind,noticeVersion,collectedAt,now(),ticket.userId);
            return true;
        }));
    }
    /** Called inside the same app_user-locked withdrawal transaction. */
    public void withdraw(long user) {
        if(settings.enabled()) jdbc.update("DELETE FROM profile_photo WHERE user_id=?",user);
    }
    private void clear(long user) {
        jdbc.update("UPDATE profile_photo SET object_key=NULL,content_hash=NULL,source_kind=NULL,notice_version=NULL,collected_at=NULL,is_public=FALSE,generation=generation+1,updated_at=? WHERE user_id=?",now(),user);
    }
    private String ownKey(long user,String version) {
        active(user,false);Row r=row(user);
        return r.key!=null && Objects.equals(r.key,"avatars/"+version+".webp")?r.key:null;
    }
    public byte[] ownImage(long user,String version) {return read(()->ownKey(user,version));}
    private String reviewKey(long toilet,long review) {
        return jdbc.query("""
                SELECT p.object_key FROM toilet_review r JOIN app_user u ON u.user_id=r.author_user_id
                JOIN profile_photo p ON p.user_id=u.user_id
                WHERE r.toilet_id=? AND r.review_id=? AND r.author_detached=FALSE AND u.status='ACTIVE'
                AND p.is_public=TRUE AND p.object_key IS NOT NULL
                """,(rs,n)->rs.getString(1),toilet,review).stream().findFirst().orElse(null);
    }
    public byte[] reviewImage(long toilet,long review) {return read(()->reviewKey(toilet,review));}
    private byte[] read(java.util.function.Supplier<String> authorizedKey) {
        if(!settings.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String key=authorizedKey.get();if(key==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        byte[] image;
        try {image=store.get(key);} catch(Exception e) {throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);}
        if(!key.equals(authorizedKey.get())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return image;
    }
    public void cleanup() {
        if(!settings.enabled()) return;
        List<Long> inactive=jdbc.query("SELECT p.user_id FROM profile_photo p JOIN app_user u ON u.user_id=p.user_id WHERE u.status='WITHDRAWN' LIMIT 50",(rs,n)->rs.getLong(1));
        for(long user:inactive) tx.executeWithoutResult(status->{
            var locked=jdbc.query("SELECT status FROM app_user WHERE user_id=? FOR UPDATE",(rs,n)->rs.getString(1),user);
            if(!locked.isEmpty() && "WITHDRAWN".equals(locked.getFirst())) withdraw(user);
        });
        List<String> candidates=jdbc.query("SELECT object_key FROM profile_photo_object WHERE created_at<? AND NOT EXISTS (SELECT 1 FROM profile_photo p WHERE p.object_key=profile_photo_object.object_key) ORDER BY created_at LIMIT 5",(rs,n)->rs.getString(1),now().minusMinutes(5));
        for(String key:candidates) {
            try {tx.executeWithoutResult(status->{
                if(jdbc.query("SELECT object_key FROM profile_photo_object WHERE object_key=? FOR UPDATE",(rs,n)->rs.getString(1),key).isEmpty()) return;
                if(jdbc.queryForObject("SELECT COUNT(*) FROM profile_photo WHERE object_key=?",Integer.class,key)>0) return;
                store.delete(key);jdbc.update("DELETE FROM profile_photo_object WHERE object_key=?",key);
            });} catch(Exception ignored) {/* Keep for the next retry. */}
        }
    }
}
