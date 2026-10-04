package com.example.toiletapi.growth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class GrowthMaintenanceCliTest {
    @Test void policyFreezeBatchApplyAndLedgerChecksAreBoundedAndIdempotent() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:growth_cli_"+UUID.randomUUID()+
                ";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY,status VARCHAR(30),auth_version BIGINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY,visibility_status VARCHAR(30))");
        jdbc.execute("CREATE TABLE toilet_review(review_id BIGINT PRIMARY KEY,review_key CHAR(36) UNIQUE,toilet_id BIGINT,author_user_id BIGINT,author_detached BOOLEAN,satisfaction INT,cleanliness INT,paper_available BOOLEAN,created_at DATETIME)");
        jdbc.execute("CREATE TABLE toilet_review_submission(user_id BIGINT,review_id BIGINT)");
        jdbc.execute("CREATE TABLE current_toilet_region(toilet_id BIGINT PRIMARY KEY,status VARCHAR(30),sigungu_code CHAR(5))");
        jdbc.execute("CREATE TABLE region_sigungu_reference(sigungu_code CHAR(5) PRIMARY KEY,sido_code CHAR(2),sido_name VARCHAR(50),sigungu_name VARCHAR(80),display_name VARCHAR(160),is_active BOOLEAN)");
        String ddl=new ClassPathResource("db/migration/V40__member_growth.sql").getContentAsString(StandardCharsets.UTF_8)
                .replace("BOOLEAN","TINYINT");
        new ResourceDatabasePopulator(new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))).execute(ds);
        jdbc.update("INSERT INTO app_user VALUES(1,'ACTIVE',0),(2,'ACTIVE',0),(3,'WITHDRAWN',0)");
        jdbc.update("INSERT INTO toilet VALUES(1,'VISIBLE'),(2,'VISIBLE')");
        jdbc.update("INSERT INTO region_sigungu_reference VALUES('30110','30','대전','동구','대전 동구',TRUE)");
        jdbc.update("INSERT INTO current_toilet_region VALUES(1,'VERIFIED','30110'),(2,'VERIFIED','30110')");
        for(int id=1;id<=2;id++) {
            jdbc.update("INSERT INTO toilet_review VALUES(?,?,?,?,FALSE,4,5,TRUE,'2026-10-01 12:00:00')",
                    id,String.format("00000000-0000-4000-8000-%012d",id),id,id);
            jdbc.update("INSERT INTO toilet_review_submission VALUES(?,?)",id,id);
        }
        var cli=new GrowthMaintenanceCli(ds,()->()->{});
        Map<String,Object> policy=cli.run(new String[]{"policy-preview"});
        assertEquals(false,policy.get("initialized"));assertEquals(1,policy.get("targetDistricts"));
        assertEquals(64,((String)policy.get("targetDigest")).length());
        assertThrows(IllegalStateException.class,()->cli.run(new String[]{"policy-initialize","2026-10-v1","2","1",
                (String)policy.get("targetDigest"),"--apply"}));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_policy_snapshot",Integer.class));
        cli.run(new String[]{"policy-initialize","2026-10-v1","1","1",(String)policy.get("targetDigest"),"--apply"});
        Map<String,Object> preview=cli.run(new String[]{"batch-preview","0","50"});
        assertEquals(2,preview.get("users"));assertEquals(60L,preview.get("expectedXp"));
        assertEquals("2",preview.get("nextAfterUserId"));
        assertThrows(IllegalArgumentException.class,()->cli.run(new String[]{"batch-preview","0","51"}));
        assertThrows(IllegalArgumentException.class,()->cli.run(new String[]{"batch-apply","2026-10-v1","0","50","0","--dry-run"}));
        Map<String,Object> applied=cli.run(new String[]{"batch-apply","2026-10-v1","0","50","0","--apply"});
        assertEquals(2,applied.get("appliedUsers"));assertEquals(60L,applied.get("totalXpForAppliedUsers"));
        assertEquals(0,cli.run(new String[]{"batch-apply","2026-10-v1","0","50","0","--apply"}).get("earnedAwards"));
        Map<String,Object> ledger=cli.run(new String[]{"ledger-check"});
        assertEquals(60L,ledger.get("totalXp"));assertEquals(0L,ledger.get("balanceMismatches"));
        assertEquals(0L,ledger.get("staleReviewEvidence"));
        jdbc.update("DELETE FROM toilet_review_submission WHERE review_id=1");
        Map<String,Object> changed=cli.run(new String[]{"batch-preview","0","50"});
        assertEquals(1,((Map<?,?>)changed.get("excludedReasons")).get("SUBMISSION_UNVERIFIED"));
        assertThrows(IllegalStateException.class,()->cli.run(new String[]{"batch-apply","2026-10-v1","0","50","0","--apply"}));
        assertEquals(30L,cli.run(new String[]{"batch-apply","2026-10-v1","0","50","1","--apply"})
                .get("totalXpForAppliedUsers"));
        jdbc.update("DELETE FROM growth_account WHERE user_id=1");
        Map<String,Object> broken=cli.run(new String[]{"ledger-check"});
        assertEquals(1L,broken.get("eventUsersWithoutAccount"));
        assertEquals(1L,broken.get("balanceMismatches"));
    }
}
