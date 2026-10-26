package com.example.toiletapi.growth;

import com.example.toiletapi.auth.privacy.EmailProtection;
import com.geupddong.growth.GrowthLevelPolicy;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Same repository contract runs in H2 and disposable MySQL; all addresses are synthetic. */
class AdminGrowthRankingDatabaseTest {
    JdbcTemplate jdbc;AdminGrowthRankingService service;AdminGrowthAccess access;EmailProtection protection;
    @BeforeEach void setup() {
        setup(new DriverManagerDataSource("jdbc:h2:mem:xp_ranking_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    }
    void setup(DataSource source) {
        jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY,status VARCHAR(20),display_name VARCHAR(100),email VARCHAR(255),email_masked VARCHAR(320),email_lookup_hash CHAR(64),auth_version BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE user_role(user_id BIGINT,role VARCHAR(30))");
        jdbc.execute("CREATE TABLE growth_account(user_id BIGINT PRIMARY KEY,total_xp BIGINT NOT NULL,updated_at TIMESTAMP(6))");
        protection=new EmailProtection(new MockEnvironment().withProperty("AUTH_EMAIL_SEARCH_KEY",Base64.getEncoder().encodeToString(new byte[32])));
        service=new AdminGrowthRankingService(new AdminGrowthRankingRepository(jdbc),protection);access=new AdminGrowthAccess(jdbc);
        member(1,"ACTIVE","관리자",null);member(2,"ACTIVE","검색 회원 둘",80L);member(3,"ACTIVE","검색 회원 셋",80L);
        member(4,"SUSPENDED","정지 회원",240L);member(5,"ACTIVE","무활동 회원",null);
        member(6,"WITHDRAWN","탈퇴 비공개",90000L);member(7,"PENDING_CONSENT","동의 대기",100000L);
        member(8,"ACTIVE","경계 회원",79L);member(9,"ACTIVE","검정 회원",15990L);
        jdbc.update("INSERT INTO user_role VALUES(1,'ADMIN')");
    }
    void member(long id,String status,String name,Long xp) {
        jdbc.update("INSERT INTO app_user(user_id,status,display_name,email) VALUES(?,?,?,?)",id,status,name,"member"+id+"@example.test");
        if(xp!=null)jdbc.update("INSERT INTO growth_account VALUES(?,?,CURRENT_TIMESTAMP)",id,xp);
    }
    AdminGrowthRankingModels.Page page(String keyword,String sort,int page,int size) {
        return service.search(new AdminGrowthRankingModels.SearchRequest(keyword,sort,page,size));
    }
    List<String> ids(AdminGrowthRankingModels.Page page) {return page.items().stream().map(AdminGrowthRankingModels.Item::id).toList();}
    Jwt admin() {return Jwt.withTokenValue("synthetic").header("alg","HS256").subject("1").claim("auth_version",0).build();}
    @Test void cohortIncludesSuspendedAndZeroXpButNeverWithdrawnOrPendingMembers() {
        var page=page(null,"xp_desc",0,50);
        assertEquals(7,page.totalElements());assertEquals(List.of("9","4","2","3","8","1","5"),ids(page));
        assertEquals(List.of(1L,2L,3L,3L,5L,6L,6L),page.items().stream().map(AdminGrowthRankingModels.Item::position).toList());
        assertEquals(0,page.items().getLast().totalXp());assertEquals("white",page.items().getLast().rank());
        assertEquals("ACTIVE_AND_SUSPENDED",page.scope());assertEquals(List.of("PENDING_CONSENT","WITHDRAWN"),page.excludedStatuses());
        assertFalse(page.toString().contains("탈퇴 비공개"));assertFalse(page.toString().contains("동의 대기"));
    }
    @Test void globalCompetitionRanksSurviveSearchAscendingOrderAndStablePages() {
        var filtered=page("검색","xp_asc",0,10);assertEquals(List.of("2","3"),ids(filtered));
        assertEquals(List.of(3L,3L),filtered.items().stream().map(AdminGrowthRankingModels.Item::position).toList());
        for(String sort:List.of("xp_asc","xp_desc")) {
            List<String> all=new ArrayList<>();for(int page=0;page<4;page++)all.addAll(ids(page(null,sort,page,2)));
            assertEquals(7,all.size());assertEquals(7,new HashSet<>(all).size());
            assertEquals(sort.equals("xp_asc")?List.of("1","5","8","2","3","4","9"):List.of("9","4","2","3","8","1","5"),all);
        }
        assertTrue(page(null,"xp_desc",100000,10).items().isEmpty());assertEquals(7,page(null,"xp_desc",100000,10).totalElements());
    }
    @Test void nameWildcardsAreLiteralAndCannotBroadenSearch() {
        member(20,"ACTIVE","이름%문자",null);member(21,"ACTIVE","이름_문자",null);member(22,"ACTIVE","이름!문자",null);
        assertEquals(List.of("20"),ids(page("%","xp_desc",0,10)));
        assertEquals(List.of("21"),ids(page("_","xp_desc",0,10)));
        assertEquals(List.of("22"),ids(page("!","xp_desc",0,10)));
        assertEquals(0,page("' OR 1=1 --","xp_desc",0,10).totalElements());
    }
    @Test void encryptedEmailUsesExactNormalizedHmacAndOnlyReturnsMaskedHint() {
        jdbc.update("UPDATE app_user SET email=NULL,email_masked=?,email_lookup_hash=? WHERE user_id=2","pr***@example.test",protection.lookupHash("private@example.test"));
        var encrypted=page("  PRIVATE@EXAMPLE.TEST  ","xp_desc",0,10);
        assertEquals(List.of("2"),ids(encrypted));assertEquals("pr***@example.test",encrypted.items().getFirst().email());
        assertEquals(3,encrypted.items().getFirst().position());assertFalse(encrypted.toString().toLowerCase(Locale.ROOT).contains("private@example.test"));
        assertEquals(List.of("3"),ids(page("MEMBER3@EXAMPLE.TEST","xp_desc",0,10)));
        assertEquals("me***@example.test",page("member3@example.test","xp_desc",0,10).items().getFirst().email());
        assertEquals(0,page("member3@","xp_desc",0,10).totalElements());
    }
    @Test void distributionUsesEveryOfficialBoundaryAndIsIndependentOfSearch() {
        jdbc.update("DELETE FROM growth_account");jdbc.update("DELETE FROM user_role");jdbc.update("DELETE FROM app_user");
        var tiers=GrowthLevelPolicy.TIERS;long id=1;
        for(int i=0;i<tiers.size();i++) {
            member(id++,"ACTIVE","등급 경계",tiers.get(i).minXp());
            if(i+1<tiers.size())member(id++,"ACTIVE","등급 상한",tiers.get(i+1).minXp()-1);
        }
        member(id++,"ACTIVE","계정 없는 0XP",null);member(id++,"SUSPENDED","정지 0XP",null);
        member(id++,"WITHDRAWN","제외",999999L);member(id,"PENDING_CONSENT","제외",999999L);
        var distribution=service.distribution();assertEquals(15,distribution.totalMembers());assertEquals(7,distribution.buckets().size());
        assertEquals(List.of(4L,2L,2L,2L,2L,2L,1L),distribution.buckets().stream().map(AdminGrowthRankingModels.Bucket::count).toList());
        assertEquals(List.of(0L,80L,240L,990L,2240L,6240L,15990L),distribution.buckets().stream().map(AdminGrowthRankingModels.Bucket::minXp).toList());
        assertNull(distribution.buckets().getLast().maxXp());
        page("결과 없음","xp_asc",0,10);assertEquals(distribution,service.distribution());
        for(var item:page(null,"xp_desc",0,50).items()) {
            assertEquals(GrowthService.level(item.totalXp()),item.level());assertEquals(GrowthLevelPolicy.tier(item.totalXp()).rank(),item.rank());
        }
    }
    @Test void repeatedReportsDoNotWriteBalancesAccountsOrRoles() {
        var users=jdbc.queryForList("SELECT * FROM app_user ORDER BY user_id");var accounts=jdbc.queryForList("SELECT * FROM growth_account ORDER BY user_id");
        var roles=jdbc.queryForList("SELECT * FROM user_role ORDER BY user_id");
        for(int i=0;i<3;i++) {access.requireAdmin(admin());page(null,"xp_desc",i,2);page("MEMBER2@EXAMPLE.TEST","xp_asc",0,10);service.distribution();}
        assertEquals(users,jdbc.queryForList("SELECT * FROM app_user ORDER BY user_id"));assertEquals(accounts,jdbc.queryForList("SELECT * FROM growth_account ORDER BY user_id"));assertEquals(roles,jdbc.queryForList("SELECT * FROM user_role ORDER BY user_id"));
    }
    @Test void currentDatabaseAdminAndSessionStateAlwaysOverrideToken() {
        assertEquals(1,access.requireAdmin(admin()));jdbc.update("DELETE FROM user_role WHERE user_id=1");
        assertEquals(403,assertThrows(ResponseStatusException.class,()->access.requireAdmin(admin())).getStatusCode().value());
        jdbc.update("INSERT INTO user_role VALUES(1,'ADMIN')");jdbc.update("UPDATE app_user SET auth_version=1 WHERE user_id=1");
        assertEquals(401,assertThrows(ResponseStatusException.class,()->access.requireAdmin(admin())).getStatusCode().value());
        jdbc.update("UPDATE app_user SET auth_version=0,status='SUSPENDED' WHERE user_id=1");
        assertEquals(401,assertThrows(ResponseStatusException.class,()->access.requireAdmin(admin())).getStatusCode().value());
        assertEquals(401,assertThrows(ResponseStatusException.class,()->access.requireAdmin(null)).getStatusCode().value());
    }
    @Test void invalidBoundsSortAndControlCharactersHaveFixedNonPrivateErrors() {
        assertThrows(IllegalArgumentException.class,()->service.search(null));
        for(String keyword:List.of("x".repeat(321),"private@example.test\nprivate"))assertThrows(IllegalArgumentException.class,()->page(keyword,"xp_desc",0,10));
        for(int page:List.of(-1,100001,Integer.MAX_VALUE))assertThrows(IllegalArgumentException.class,()->page(null,"xp_desc",page,10));
        for(int size:List.of(0,-1,51,Integer.MAX_VALUE))assertThrows(IllegalArgumentException.class,()->page(null,"xp_desc",0,size));
        assertThrows(IllegalArgumentException.class,()->page(null,"total_xp DESC; DELETE",0,10));
        assertEquals(10,service.search(new AdminGrowthRankingModels.SearchRequest(null,null,null,null)).size());
    }
}
