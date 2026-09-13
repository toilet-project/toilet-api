package com.example.toiletapi.photo;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class PhotoServiceTest {
    JdbcTemplate jdbc;PhotoService photos;TransactionTemplate tx;MemoryStore store;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:photos_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        setup(ds);
    }
    void setup(DataSource ds) {
        jdbc=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        jdbc.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY,status VARCHAR(20),auth_version BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE toilet_review(review_id BIGINT PRIMARY KEY,toilet_id BIGINT,author_user_id BIGINT,author_detached BOOLEAN DEFAULT FALSE)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V13__social_profile_photos.sql")).execute(ds);
        jdbc.update("INSERT INTO app_user VALUES(1,'ACTIVE',0),(2,'ACTIVE',0)");
        jdbc.update("INSERT INTO toilet_review(review_id,toilet_id,author_user_id) VALUES(10,20,1)");
        store=new MemoryStore();photos=new PhotoService(new PhotoSettings(true,null,null,null,null,null,null),jdbc,manager,store);
    }
    byte[] bytes="RIFFtestWEBPcontent".getBytes(StandardCharsets.US_ASCII);
    void upload(long user) throws Exception {
        assertTrue(photos.saveWithReceipt(photos.uploadTicket(user),bytes,"DIRECT_UPLOAD",PhotoService.NOTICE_VERSION,LocalDateTime.now()));
    }
    @Test void privateDefaultOwnershipVisibilityAndDelete() throws Exception {
        assertNull(photos.state(1).imageVersion());assertFalse(photos.state(1).publicPhoto());
        upload(1);String version=photos.state(1).imageVersion();
        assertArrayEquals(bytes,photos.ownImage(1,version));
        assertThrows(ResponseStatusException.class,()->photos.ownImage(2,version));
        assertThrows(ResponseStatusException.class,()->photos.reviewImage(20,10));
        photos.visibility(1,true);assertArrayEquals(bytes,photos.reviewImage(20,10));
        photos.visibility(1,false);assertThrows(ResponseStatusException.class,()->photos.reviewImage(20,10));
        assertArrayEquals(bytes,photos.ownImage(1,version));
        photos.delete(1);assertNull(photos.state(1).imageVersion());
    }
    @Test void revokeDuringStorageReadAndDetachedReview() throws Exception {
        upload(1);photos.visibility(1,true);
        store.onRead=()->photos.visibility(1,false);
        assertThrows(ResponseStatusException.class,()->photos.reviewImage(20,10));
        store.onRead=()->{};photos.visibility(1,true);
        jdbc.update("UPDATE toilet_review SET author_detached=TRUE WHERE review_id=10");
        assertThrows(ResponseStatusException.class,()->photos.reviewImage(20,10));
    }
    @Test void pendingUploadCannotUndoDeleteWithdrawalOrNewerUpload() throws Exception {
        var old=photos.uploadTicket(1);photos.delete(1);
        assertFalse(photos.saveWithReceipt(old,bytes,"DIRECT_UPLOAD",PhotoService.NOTICE_VERSION,LocalDateTime.now()));
        assertNull(photos.state(1).imageVersion());
        var withdrawn=photos.uploadTicket(1);
        tx.executeWithoutResult(s->{jdbc.update("UPDATE app_user SET status='WITHDRAWN',auth_version=1 WHERE user_id=1");photos.withdraw(1);});
        jdbc.update("UPDATE app_user SET status='ACTIVE' WHERE user_id=1");
        assertFalse(photos.saveWithReceipt(withdrawn,bytes,"DIRECT_UPLOAD",PhotoService.NOTICE_VERSION,LocalDateTime.now()));
        var first=photos.uploadTicket(1);var newer=photos.uploadTicket(1);
        assertFalse(photos.saveWithReceipt(first,bytes,"DIRECT_UPLOAD",PhotoService.NOTICE_VERSION,LocalDateTime.now()));
        assertTrue(photos.saveWithReceipt(newer,bytes,"DIRECT_UPLOAD",PhotoService.NOTICE_VERSION,LocalDateTime.now()));
    }
    @Test void signupImportRunsOnlyForUntouchedFirstActivation() throws Exception {
        var signup=photos.signupTicket(1);assertNotNull(signup);assertNull(photos.signupTicket(1));
        assertTrue(photos.saveWithReceipt(signup,bytes,"KAKAO_SIGNUP",PhotoService.NOTICE_VERSION,LocalDateTime.now()));
        assertFalse(photos.state(1).publicPhoto());
        var manual=photos.uploadTicket(2);assertNull(photos.signupTicket(2));
        assertTrue(photos.saveWithReceipt(manual,bytes,"DIRECT_UPLOAD",PhotoService.NOTICE_VERSION,LocalDateTime.now()));
    }
    @Test void batchDeletionCascadesAndFailedStorageDeletionRemainsRetryable() throws Exception {
        upload(1);assertEquals(1,store.images.size());ageObjects();
        jdbc.update("DELETE FROM app_user WHERE user_id=1");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM profile_photo",Integer.class));
        store.failDelete=true;photos.cleanup();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM profile_photo_object",Integer.class));
        store.failDelete=false;photos.cleanup();assertTrue(store.images.isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM profile_photo_object",Integer.class));
    }
    @Test void failedAndStalePutsCannotBecomeVisible() {
        var ticket=photos.uploadTicket(1);store.failPut=true;
        assertThrows(IllegalStateException.class,()->photos.saveWithReceipt(ticket,bytes,"DIRECT_UPLOAD",PhotoService.NOTICE_VERSION,LocalDateTime.now()));
        assertNull(photos.state(1).imageVersion());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM profile_photo_object",Integer.class));
        store.failPut=false;store.onPut=this::ageObjects;
        assertDoesNotThrow(()->assertFalse(photos.saveWithReceipt(ticket,bytes,"DIRECT_UPLOAD",PhotoService.NOTICE_VERSION,LocalDateTime.now())));
        assertNull(photos.state(1).imageVersion());
    }
    private void ageObjects() {
        jdbc.update("UPDATE profile_photo_object SET created_at=?",java.sql.Timestamp.valueOf(LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul")).minusMinutes(10)));
    }
    @Test void disabledDoesNotQueryUnmigratedDatabase() {
        var disabled=new PhotoService(new PhotoSettings(false,null,null,null,null,null,null),null,nullManager(),store);
        assertFalse(disabled.state(1).available());disabled.withdraw(1);disabled.cleanup();assertNull(disabled.signupTicket(1));
    }
    private org.springframework.transaction.PlatformTransactionManager nullManager() {return new DataSourceTransactionManager(new DriverManagerDataSource());}
    static class MemoryStore implements PhotoStore {
        Map<String,byte[]> images=new HashMap<>();boolean failDelete,failPut;Runnable onRead=()->{},onPut=()->{};
        public void put(String key,byte[] data) {images.put(key,data);onPut.run();if(failPut)throw new IllegalStateException();}
        public byte[] get(String key) {onRead.run();return images.get(key);}
        public void delete(String key) {if(failDelete)throw new IllegalStateException();images.remove(key);}
    }
}
