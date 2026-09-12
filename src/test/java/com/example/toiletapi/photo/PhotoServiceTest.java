package com.example.toiletapi.photo;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class PhotoServiceTest {
    JdbcTemplate jdbc;PhotoService photos;TransactionTemplate tx;
    MemoryStore store;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:photos_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        jdbc=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        jdbc.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY,status VARCHAR(20),auth_version BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE toilet_review(review_id BIGINT PRIMARY KEY,toilet_id BIGINT,author_user_id BIGINT,author_detached BOOLEAN DEFAULT FALSE)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V13__social_profile_photos.sql")).execute(ds);
        jdbc.update("INSERT INTO app_user VALUES(1,'ACTIVE',0),(2,'ACTIVE',0)");
        jdbc.update("INSERT INTO toilet_review(review_id,toilet_id,author_user_id) VALUES(10,20,1)");
        store=new MemoryStore();photos=new PhotoService(new PhotoSettings(true,null,null,null,null,null,null),jdbc,manager,store);
    }
    byte[] bytes="RIFFtestWEBPcontent".getBytes(StandardCharsets.US_ASCII);
    void upload(long user) {photos.update(user,true,false);photos.save(photos.ticket(user,"source"),bytes,"hash","source");}
    @Test void privateDefaultAndOwnership() {
        assertFalse(photos.state(1).useSocial());assertFalse(photos.state(1).publicPhoto());
        upload(1);String version=photos.state(1).imageVersion();
        assertArrayEquals(bytes,photos.ownImage(1,version));
        assertThrows(ResponseStatusException.class,()->photos.ownImage(2,version));
        assertThrows(ResponseStatusException.class,()->photos.reviewImage(20,10));
        photos.update(1,true,true);assertArrayEquals(bytes,photos.reviewImage(20,10));
        photos.update(1,true,false);assertThrows(ResponseStatusException.class,()->photos.reviewImage(20,10));
        assertArrayEquals(bytes,photos.ownImage(1,version));
    }
    @Test void revokeDuringStorageReadAndDetachedReview() {
        upload(1);photos.update(1,true,true);
        store.onRead=()->photos.update(1,true,false);
        assertThrows(ResponseStatusException.class,()->photos.reviewImage(20,10));
        store.onRead=()->{};photos.update(1,true,true);
        jdbc.update("UPDATE toilet_review SET author_detached=TRUE WHERE review_id=10");
        assertThrows(ResponseStatusException.class,()->photos.reviewImage(20,10));
    }
    @Test void pendingUploadCannotUndoWithdrawalOrPreferenceChange() {
        photos.update(1,true,true);var ticket=photos.ticket(1,"source");
        photos.update(1,false,false);photos.update(1,true,false);
        photos.save(ticket,bytes,"hash","source");assertNull(photos.state(1).imageVersion());
        var next=photos.ticket(1,"new");
        tx.executeWithoutResult(s->{jdbc.update("UPDATE app_user SET status='WITHDRAWN',auth_version=1 WHERE user_id=1");photos.withdraw(1);});
        jdbc.update("UPDATE app_user SET status='ACTIVE' WHERE user_id=1");
        photos.update(1,true,true);photos.save(next,bytes,"hash","new");assertNull(photos.state(1).imageVersion());
    }
    @Test void batchDeletionCascadesAndFailedStorageDeletionRemainsRetryable() {
        upload(1);assertEquals(1,store.images.size());
        jdbc.update("UPDATE profile_photo_object SET created_at=DATEADD('MINUTE',-10,CURRENT_TIMESTAMP)");
        // Use the same deletion boundary as API, batch and erasure replay.
        jdbc.update("DELETE FROM app_user WHERE user_id=1");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM profile_photo",Integer.class));
        store.failDelete=true;photos.cleanup();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM profile_photo_object",Integer.class));
        store.failDelete=false;photos.cleanup();assertTrue(store.images.isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM profile_photo_object",Integer.class));
    }
    @Test void absentPhotoClearsAndHashDeduplicates() {
        upload(1);assertNull(photos.ticket(1,"source"));
        var ticket=photos.ticket(1,"changed-source");assertTrue(photos.unchanged(ticket,"hash","changed-source"));
        assertEquals(1,store.images.size());photos.absent(photos.ticket(1,"absent"));
        assertNull(photos.state(1).imageVersion());assertNull(photos.ticket(1,"absent"));
    }
    @Test void failedAndStalePutsCannotBecomeVisible() {
        photos.update(1,true,true);var ticket=photos.ticket(1,"s");store.failPut=true;
        assertThrows(IllegalStateException.class,()->photos.save(ticket,bytes,"h","s"));
        assertNull(photos.state(1).imageVersion());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM profile_photo_object",Integer.class));
        store.failPut=false;store.onPut=()->jdbc.update("UPDATE profile_photo_object SET created_at=DATEADD('MINUTE',-10,CURRENT_TIMESTAMP)");
        photos.save(ticket,bytes,"h","s");assertNull(photos.state(1).imageVersion());
    }
    @Test void disabledDoesNotQueryUnmigratedDatabase() {
        var disabled=new PhotoService(new PhotoSettings(false,null,null,null,null,null,null),null,nullManager(),store);
        assertFalse(disabled.state(1).available());disabled.withdraw(1);disabled.cleanup();assertNull(disabled.ticket(1,"s"));
    }
    private org.springframework.transaction.PlatformTransactionManager nullManager() {return new DataSourceTransactionManager(new DriverManagerDataSource());}
    static class MemoryStore implements PhotoStore {
        Map<String,byte[]> images=new HashMap<>();boolean failDelete,failPut;Runnable onRead=()->{},onPut=()->{};
        public void put(String key,byte[] data) {images.put(key,data);onPut.run();if(failPut)throw new IllegalStateException();}
        public byte[] get(String key) {onRead.run();return images.get(key);}
        public void delete(String key) {if(failDelete)throw new IllegalStateException();images.remove(key);}
    }
}
