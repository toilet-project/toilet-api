package com.example.toiletapi.review;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class WrittenReviewsPreviewServerTest {
    Map<String,Object> snapshot() {
        var review=new LinkedHashMap<String,Object>();
        review.put("id","10");review.put("toiletId",1);review.put("authorUserId","2");review.put("authorDetached",false);
        review.put("satisfaction",5);review.put("cleanliness",3);review.put("paper",true);review.put("waitMinutes",10);
        review.put("comment","😀".repeat(200));review.put("createdAt","2026-10-24T12:00:00.123456");review.put("updatedAt","2026-10-24T13:00:00.123456");
        var result=new LinkedHashMap<String,Object>();
        result.put("capturedAt",Instant.now().toString());result.put("reviews",List.of(review));
        result.put("toilets",List.of(Map.of("id",1,"name","실데이터 시설","visibilityStatus","VISIBLE")));
        result.put("authors",List.of(Map.of("id","2","status","ACTIVE","displayName","작*")));
        return result;
    }
    Jwt fixtureAdmin() {return Jwt.withTokenValue("fixture").header("alg","HS256").subject(Long.toString(Long.MAX_VALUE)).claim("auth_version",0).build();}
    @Test void snapshotUsesRealControllerAndSqlButNeverLoadsAnOperationalDatasource() throws Exception {
        var fixture=WrittenReviewsPreviewServer.loadSnapshot(WrittenReviewsPreviewServer.JSON.writeValueAsString(snapshot()));
        var page=fixture.controller().page(fixtureAdmin(),null,20);
        assertEquals("10",page.items().getFirst().id());assertEquals("작*",page.items().getFirst().authorDisplayName());
        assertEquals("😀".repeat(200),fixture.controller().detail(fixtureAdmin(),10).comment());
        assertTrue(fixture.jdbc().getDataSource().getConnection().getMetaData().getURL().startsWith("jdbc:h2:mem:written_reviews_preview_"));
    }
    @Test void nonActiveDisplayNamesAreClearedWhileStatusKeepsOperationalLabelsAccurate() throws Exception {
        var inactive=snapshot();inactive.put("authors",List.of(Map.of("id","2","status","WITHDRAWN","displayName","옛 이름")));
        var withdrawn=WrittenReviewsPreviewServer.loadSnapshot(WrittenReviewsPreviewServer.JSON.writeValueAsString(inactive));
        assertEquals("탈퇴한 사용자",withdrawn.controller().detail(fixtureAdmin(),10).authorDisplayName());
        assertNull(withdrawn.jdbc().queryForObject("SELECT display_name FROM app_user WHERE user_id=2",String.class));
        inactive.put("authors",List.of(Map.of("id","2","status","SUSPENDED","displayName","비활성 이름")));
        var suspended=WrittenReviewsPreviewServer.loadSnapshot(WrittenReviewsPreviewServer.JSON.writeValueAsString(inactive));
        assertEquals("급똥 사용자",suspended.controller().detail(fixtureAdmin(),10).authorDisplayName());
        assertTrue(suspended.controller().detail(fixtureAdmin(),10).authorRemoved());
        assertNull(suspended.jdbc().queryForObject("SELECT display_name FROM app_user WHERE user_id=2",String.class));
        inactive.put("authors",List.of(Map.of("id","2","status","UNKNOWN","displayName","허용 안 됨")));
        assertThrows(IllegalArgumentException.class,()->WrittenReviewsPreviewServer.loadSnapshot(WrittenReviewsPreviewServer.JSON.writeValueAsString(inactive)));
    }
    @Test void staleOrOversizedSnapshotsAreRejected() throws Exception {
        var stale=snapshot();stale.put("capturedAt",Instant.now().minusSeconds(7300).toString());
        assertThrows(IllegalArgumentException.class,()->WrittenReviewsPreviewServer.loadSnapshot(WrittenReviewsPreviewServer.JSON.writeValueAsString(stale)));
        var large=snapshot();large.put("reviews",Collections.nCopies(5001,((List<?>)large.get("reviews")).getFirst()));
        assertThrows(IllegalArgumentException.class,()->WrittenReviewsPreviewServer.loadSnapshot(WrittenReviewsPreviewServer.JSON.writeValueAsString(large)));
    }
    @Test void detachedAuthorCannotReappearEvenIfExportSuppliedAnIdentifier() throws Exception {
        var detached=snapshot();
        @SuppressWarnings("unchecked") var review=(Map<String,Object>)((List<?>)detached.get("reviews")).getFirst();
        review.put("authorDetached",true);
        var fixture=WrittenReviewsPreviewServer.loadSnapshot(WrittenReviewsPreviewServer.JSON.writeValueAsString(detached));
        var item=fixture.controller().detail(fixtureAdmin(),10);
        assertEquals("익명",item.authorDisplayName());assertTrue(item.authorRemoved());
        assertNull(fixture.jdbc().queryForObject("SELECT author_user_id FROM toilet_review WHERE review_id=10",Long.class));
    }
}
