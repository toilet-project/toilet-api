package com.example.toiletapi.review;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.jwt.Jwt;

class AdminReviewDatabaseTest {
    JdbcTemplate jdbc;
    AdminReviewService service;
    AdminReviewAccess access;
    final LocalDateTime now=LocalDateTime.of(2026,10,24,12,0,0,123456000);
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:admin_reviews_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        setup(ds);
    }
    void setup(javax.sql.DataSource ds) {
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY,status VARCHAR(20),display_name VARCHAR(100),auth_version BIGINT)");
        jdbc.execute("CREATE TABLE user_role(user_id BIGINT,role VARCHAR(20))");
        jdbc.execute("CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY,name VARCHAR(100),visibility_status VARCHAR(24))");
        jdbc.execute("""
                CREATE TABLE toilet_review(review_id BIGINT PRIMARY KEY,toilet_id BIGINT,author_user_id BIGINT,
                author_detached BOOLEAN,satisfaction INT,cleanliness INT,paper_available BOOLEAN,wait_minutes INT,
                comment VARCHAR(200),created_at TIMESTAMP(6),updated_at TIMESTAMP(6),review_key CHAR(36),version BIGINT NOT NULL DEFAULT 0)
                """);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V45__index_admin_review_created.sql")).execute(ds);
        jdbc.update("INSERT INTO app_user VALUES(1,'ACTIVE','관리자',3),(2,'ACTIVE','작성자',0),(3,'WITHDRAWN','사라져야 할 이름',1),(4,'SUSPENDED','비활성 이름',0)");
        jdbc.update("INSERT INTO user_role VALUES(1,'ADMIN')");
        jdbc.update("INSERT INTO toilet VALUES(1,'서울역 화장실','VISIBLE'),(2,'숨김 화장실','HIDDEN_DUPLICATE')");
        service=new AdminReviewService(new AdminReviewRepository(jdbc));access=new AdminReviewAccess(jdbc);
    }
    void insert(long id,long toilet,Long author,boolean detached,LocalDateTime time,String comment) {
        jdbc.update("INSERT INTO toilet_review(review_id,toilet_id,author_user_id,author_detached,satisfaction,cleanliness,paper_available,wait_minutes,comment,created_at,updated_at,review_key) VALUES(?,?,?,?,4,2,TRUE,20,?,?,?,?)",
                id,toilet,author,detached,comment,time,time.plusHours(1),new UUID(0,id).toString());
    }
    Jwt admin() { return Jwt.withTokenValue("synthetic").header("alg","HS256").subject("1").claim("auth_version",3).build(); }

    @Test void newestOrderingUsesCreatedTimeBeforeIdAndPreservesMicrosecondTieBreaks() {
        insert(20,1,2L,false,now.minusNanos(1000),"이전 마이크로초");
        insert(8,1,2L,false,now,"본문 하나");insert(9,1,2L,false,now,"본문 둘");
        var first=service.page(null,1);
        assertEquals("9",first.items().getFirst().id());assertTrue(first.hasMore());assertNotNull(first.nextCursor());
        assertEquals(now,ReviewCursor.parse(first.nextCursor()).createdAt());
        assertEquals(now.atOffset(ZoneOffset.ofHours(9)),first.items().getFirst().createdAt());
        var second=service.page(first.nextCursor(),1);
        assertEquals("8",second.items().getFirst().id());assertTrue(second.hasMore());
        var last=service.page(second.nextCursor(),1);
        assertEquals("20",last.items().getFirst().id());assertFalse(last.hasMore());assertNull(last.nextCursor());
    }
    @Test void newReviewsBetweenPagesDoNotRepeatAlreadyVisibleRows() {
        insert(1,1,2L,false,now.minusDays(1),"오래된 글");insert(2,1,2L,false,now,"첫화면");
        var first=service.page(null,1);
        insert(3,1,2L,false,now.plusSeconds(1),"방금 등록");
        assertEquals(List.of("1"),service.page(first.nextCursor(),20).items().stream().map(AdminReviewModels.Item::id).toList());
        assertEquals("3",service.page(null,20).items().getFirst().id());
    }
    @Test void hiddenToiletAndUnlinkedWithdrawnOrErasedAuthorsRemainVisibleWithoutOldNames() {
        insert(1,2,2L,false,now,"숨김 시설 보존");insert(2,1,2L,true,now,"익명");
        insert(3,1,3L,false,now,"탈퇴");insert(4,1,null,false,now,"영구 삭제");
        insert(5,1,4L,false,now,"비활성");
        var items=service.page(null,20).items();
        assertEquals(5,items.size());
        assertEquals("급똥 사용자",items.get(0).authorDisplayName());assertTrue(items.get(0).authorRemoved());
        assertEquals("탈퇴한 사용자",items.get(1).authorDisplayName());assertTrue(items.get(1).authorRemoved());
        assertEquals("탈퇴한 사용자",items.get(2).authorDisplayName());
        assertEquals("익명",items.get(3).authorDisplayName());assertTrue(items.get(3).authorRemoved());
        assertEquals("작성자",items.get(4).authorDisplayName());assertFalse(items.get(4).authorRemoved());
        assertEquals("HIDDEN_DUPLICATE",items.get(4).toiletVisibility());
        assertEquals("숨김 시설 보존",service.detail(1).comment());
        assertFalse(items.toString().contains("사라져야"));assertFalse(items.toString().contains("비활성 이름"));
    }
    @Test void listingOmitsBodiesAndDetailReturnsLiteralTextWithoutMutation() {
        String comment="<script>alert('문자열')</script>\n줄바꿈";
        insert(1,1,2L,false,now,comment);insert(2,1,2L,false,now,"");
        var items=service.page(null,50).items();
        assertFalse(items.getFirst().hasComment());assertTrue(items.getLast().hasComment());
        assertFalse(items.toString().contains(comment));
        assertEquals(comment,service.detail(1).comment());
        assertEquals(now.plusHours(1).atOffset(ZoneOffset.ofHours(9)),service.detail(1).updatedAt());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review",Integer.class));
    }
    @Test void emptyPageHasNoCursorAndValidatesBoundedSizeAndUntrustedCursors() {
        var empty=service.page(null,20);assertTrue(empty.items().isEmpty());assertFalse(empty.hasMore());assertNull(empty.nextCursor());
        for(int size:List.of(-1,0,51,100000))assertThrows(IllegalArgumentException.class,()->service.page(null,size));
        for(String cursor:List.of("!", "a".repeat(101),Base64.getUrlEncoder().encodeToString("2026-10-24T12:00|-1".getBytes())))
            assertThrows(IllegalArgumentException.class,()->service.page(cursor,20));
        assertThrows(IllegalArgumentException.class,()->service.detail(0));
        assertEquals(404,assertThrows(ReviewFailure.class,()->service.detail(999)).status());
    }
    @Test void currentAccountAndRoleAreRecheckedDespiteAdminToken() {
        access.requireAdmin(admin());
        jdbc.update("DELETE FROM user_role WHERE user_id=1");
        assertEquals(403,assertThrows(ReviewFailure.class,()->access.requireAdmin(admin())).status());
        jdbc.update("INSERT INTO user_role VALUES(1,'ADMIN')");jdbc.update("UPDATE app_user SET auth_version=4 WHERE user_id=1");
        assertEquals(401,assertThrows(ReviewFailure.class,()->access.requireAdmin(admin())).status());
        jdbc.update("UPDATE app_user SET auth_version=3,status='WITHDRAWN' WHERE user_id=1");
        assertEquals(401,assertThrows(ReviewFailure.class,()->access.requireAdmin(admin())).status());
        assertEquals(401,assertThrows(ReviewFailure.class,()->access.requireAdmin(null)).status());
    }
}
