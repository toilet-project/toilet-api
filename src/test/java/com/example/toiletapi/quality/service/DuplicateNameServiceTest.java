package com.example.toiletapi.quality.service;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import static com.example.toiletapi.quality.dto.DuplicateNameModels.*;
import static org.junit.jupiter.api.Assertions.*;

class DuplicateNameServiceTest {
    JdbcTemplate db;DuplicateNameService service;
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:duplicates"+System.nanoTime()+";MODE=MySQL","sa","");
        db=new JdbcTemplate(ds);
        // Hold an anchor connection: this fixture intentionally uses an in-memory-only database.
        ds.setUrl(ds.getUrl()+";DB_CLOSE_DELAY=-1");
        db.execute("CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY,name VARCHAR(100),mng_no VARCHAR(50),road_address VARCHAR(255),jibun_address VARCHAR(255),latitude DECIMAL(10,7),longitude DECIMAL(10,7),coordinate_source VARCHAR(30),open_time VARCHAR(50),data_source VARCHAR(20),visibility_status VARCHAR(24) DEFAULT 'VISIBLE',representative_toilet_id BIGINT,visibility_version BIGINT DEFAULT 0,hidden_event_id BIGINT)");
        db.execute("CREATE TABLE toilet_visibility_event(event_id BIGINT AUTO_INCREMENT PRIMARY KEY,toilet_id BIGINT,representative_toilet_id BIGINT,action VARCHAR(24),reason VARCHAR(500),actor_user_id BIGINT,occurred_at DATETIME,previous_version BIGINT,snapshot_name VARCHAR(100),snapshot_road_address VARCHAR(255),snapshot_jibun_address VARCHAR(255),snapshot_latitude DECIMAL(10,7),snapshot_longitude DECIMAL(10,7))");
        db.execute("CREATE TABLE public_data_change_review(active_toilet_id BIGINT,status VARCHAR(20),status_reason VARCHAR(500),version BIGINT)");
        db.execute("CREATE TABLE current_toilet_region(toilet_id BIGINT PRIMARY KEY,sigungu_code VARCHAR(5),sido_name VARCHAR(50),sigungu_name VARCHAR(100))");
        db.execute("CREATE TABLE duplicate_name_work_visibility(admin_user_id BIGINT,group_name VARCHAR(100),work_hidden BOOLEAN,version BIGINT,updated_at DATETIME,PRIMARY KEY(admin_user_id,group_name))");
        db.update("INSERT INTO toilet(toilet_id,name) VALUES(1,'같은 이름'),(2,'같은 이름'),(3,'같은 이름'),(4,'다른 이름')");
        service=new DuplicateNameService(new NamedParameterJdbcTemplate(ds));
    }
    @Test void coordinateHideAllowsDifferentNamesAndKeepsAuditedEvidence(){
        db.update("UPDATE toilet SET latitude=37.1,longitude=127.1 WHERE toilet_id IN(1,4)");
        var result=service.hideAtCoordinates(9,new HideRequest(1,List.of(4L),Map.of(1L,0L,4L,0L),"주소 및 출입구 확인"));
        assertEquals(2,result.size());assertEquals("VISIBLE",status(1));assertEquals("HIDDEN_DUPLICATE",status(4));assertEquals("VISIBLE",status(2));
        assertEquals("주소 및 출입구 확인",service.history(4).getFirst().reason());
        assertEquals("다른 이름",result.get(1).name());
        assertThrows(ResponseStatusException.class,()->service.hideAtCoordinates(9,new HideRequest(1,List.of(4L),Map.of(1L,0L,4L,0L),"중복 요청")));
        service.restore(9,4,new RestoreRequest(1,"시험 해제"));assertEquals("VISIBLE",status(4));
    }
    @Test void coordinateHideRejectsMissingInvalidDifferentCoordinatesAndStaleVersionsBeforeAnyWrite(){
        var request=new HideRequest(1,List.of(2L,4L),Map.of(1L,0L,2L,0L,4L,0L),"동일 시설");
        assertThrows(IllegalArgumentException.class,()->service.hideAtCoordinates(9,request));
        db.update("UPDATE toilet SET latitude=0,longitude=0");
        assertThrows(IllegalArgumentException.class,()->service.hideAtCoordinates(9,request));
        db.update("UPDATE toilet SET latitude=37.1,longitude=127.1");
        db.update("UPDATE toilet SET longitude=127.1000001 WHERE toilet_id=4");
        assertThrows(IllegalArgumentException.class,()->service.hideAtCoordinates(9,request));
        db.update("UPDATE toilet SET longitude=127.1");
        assertThrows(ResponseStatusException.class,()->service.hideAtCoordinates(9,new HideRequest(1,List.of(2L),Map.of(1L,0L,2L,1L),"오래된 화면")));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM toilet_visibility_event",Integer.class));
        assertThrows(IllegalArgumentException.class,()->service.hide(9,new HideRequest(1,List.of(4L),Map.of(1L,0L,4L,0L),"이름 경로는 제한 유지")));
    }
    @Test void onlySelectedRowsHiddenAndReasonSurvivesRestore(){
        assertEquals(1,service.groups("",false,0,20).totalElements());
        service.hide(9,new HideRequest(1,List.of(2L),Map.of(1L,0L,2L,0L),"주소와 출입구 확인"));
        assertEquals("VISIBLE",status(1));assertEquals("HIDDEN_DUPLICATE",status(2));assertEquals("VISIBLE",status(3));
        assertEquals("주소와 출입구 확인",service.facilities("같은 이름").get(1).hiddenReason());
        service.restore(9,2,new RestoreRequest(1,"다른 층 시설로 재확인"));
        assertEquals("VISIBLE",status(2));assertEquals(2,service.history(2).size());
        assertEquals("주소와 출입구 확인",service.history(2).get(1).reason());
    }
    @Test void coordinateFilterPartitionsNamesAndExcludesMissingCoordinates(){
        db.update("UPDATE toilet SET latitude=37.1,longitude=127.1 WHERE toilet_id IN(1,2)");
        db.update("UPDATE toilet SET latitude=35.1,longitude=129.1 WHERE toilet_id=3");
        var groups=service.groups("",false,0,20,Comparison.COORDINATES);
        assertEquals(1,groups.totalElements());assertEquals(2,groups.items().getFirst().total());
        assertEquals(0,new java.math.BigDecimal("37.1").compareTo(groups.items().getFirst().latitude()));
        db.update("UPDATE toilet SET latitude=NULL WHERE toilet_id=2");
        assertEquals(0,service.groups("",false,0,20,Comparison.COORDINATES).totalElements());
        db.update("UPDATE toilet SET latitude=0,longitude=0");
        assertEquals(0,service.groups("",false,0,20,Comparison.COORDINATES).totalElements());
    }
    @Test void workHideIsPerAdminPersistentAndDoesNotChangePublicVisibility(){
        var result=service.setWorkVisibility(9,new WorkVisibilityRequest(" 같은 이름 ",true,0));
        assertTrue(result.hidden());assertEquals(1,result.version());
        assertEquals(0,service.groups("",false,0,20,Comparison.ALL,9,WorkVisibility.VISIBLE).totalElements());
        assertEquals(1,service.groups("",false,0,20,Comparison.ALL,10,WorkVisibility.VISIBLE).totalElements());
        var hidden=service.groups("",false,0,20,Comparison.ALL,9,WorkVisibility.HIDDEN);
        assertEquals(1,hidden.totalElements());assertTrue(hidden.items().getFirst().workHidden());assertEquals(1,hidden.items().getFirst().workVersion());
        var reconnected=new DuplicateNameService(new NamedParameterJdbcTemplate(Objects.requireNonNull(db.getDataSource())));
        assertEquals(0,reconnected.groups("",false,0,20,Comparison.ALL,9,WorkVisibility.VISIBLE).totalElements());
        assertEquals(3,reconnected.facilities("같은 이름").size());assertEquals("VISIBLE",status(1));assertEquals("VISIBLE",status(2));assertTrue(service.history(1).isEmpty());
        assertThrows(ResponseStatusException.class,()->service.setWorkVisibility(9,new WorkVisibilityRequest("같은 이름",false,0)));
        assertEquals(1,service.setWorkVisibility(9,new WorkVisibilityRequest("같은 이름",true,1)).version());
        assertEquals(2,service.setWorkVisibility(9,new WorkVisibilityRequest("같은 이름",false,1)).version());
        assertEquals(1,service.groups("",false,0,20,Comparison.ALL,9,WorkVisibility.VISIBLE).totalElements());
        assertEquals(0,service.groups("",false,0,20,Comparison.ALL,9,WorkVisibility.HIDDEN).totalElements());
    }
    @Test void workHideAppliesAcrossComparisonFiltersWithoutChangingFacilityHide(){
        db.update("UPDATE toilet SET latitude=37.1,longitude=127.1 WHERE toilet_id IN(1,2)");
        db.update("INSERT INTO current_toilet_region VALUES(1,'11140','서울특별시','중구'),(2,'11140','서울특별시','중구')");
        service.setWorkVisibility(9,new WorkVisibilityRequest("같은 이름",true,0));
        for(var mode:Comparison.values()){
            assertEquals(0,service.groups("",false,0,1,mode,9,WorkVisibility.VISIBLE).totalElements());
            assertEquals(1,service.groups("",false,0,1,mode,9,WorkVisibility.ALL).totalElements());
        }
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM toilet_visibility_event",Integer.class));
        assertThrows(ResponseStatusException.class,()->service.setWorkVisibility(9,new WorkVisibilityRequest("존재하지않음",true,0)));
        assertThrows(IllegalArgumentException.class,()->service.setWorkVisibility(9,new WorkVisibilityRequest(" ",true,0)));
        assertThrows(IllegalArgumentException.class,()->service.setWorkVisibility(9,new WorkVisibilityRequest("같은 이름",null,0)));
    }
    @Test void districtFilterUsesCodesNotSharedDistrictNamesAndExcludesUnknown(){
        db.update("INSERT INTO current_toilet_region VALUES(1,'11140','서울특별시','중구'),(2,'11140','서울특별시','중구'),(3,'26110','부산광역시','중구')");
        var groups=service.groups("",false,0,20,Comparison.DISTRICT);
        assertEquals(1,groups.totalElements());assertEquals(2,groups.items().getFirst().total());
        assertEquals("11140",groups.items().getFirst().sigunguCode());assertEquals("서울특별시 중구",groups.items().getFirst().regionName());
        assertEquals("부산광역시 중구",service.facilities("같은 이름").get(2).regionName());
        service.hide(9,new HideRequest(1,List.of(2L),Map.of(1L,0L,2L,0L),"동일"));
        assertEquals(0,service.groups("",false,0,20,Comparison.DISTRICT).totalElements());
        assertEquals(1,service.groups("",true,0,20,Comparison.DISTRICT).totalElements());
        db.update("DELETE FROM current_toilet_region WHERE toilet_id=2");
        assertEquals(0,service.groups("",true,0,20,Comparison.DISTRICT).totalElements());
    }
    @Test void rejectsSelfStaleAndCrossGroupSelections(){
        assertThrows(IllegalArgumentException.class,()->service.hide(9,new HideRequest(1,List.of(1L),Map.of(1L,0L),"이유")));
        assertThrows(ResponseStatusException.class,()->service.hide(9,new HideRequest(1,List.of(2L),Map.of(1L,0L,2L,2L),"이유")));
        assertThrows(IllegalArgumentException.class,()->service.hide(9,new HideRequest(1,List.of(4L),Map.of(1L,0L,4L,0L),"이유")));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM toilet_visibility_event",Integer.class));
    }
    @Test void cannotHideRepresentativeOrCreateChain(){
        service.hide(9,new HideRequest(1,List.of(2L),Map.of(1L,0L,2L,0L),"동일"));
        assertThrows(ResponseStatusException.class,()->service.hide(9,new HideRequest(3,List.of(1L),Map.of(1L,0L,3L,0L),"동일")));
        assertThrows(ResponseStatusException.class,()->service.hide(9,new HideRequest(2,List.of(3L),Map.of(2L,1L,3L,0L),"동일")));
    }
    @Test void completedGroupLeavesDefaultQueueButIsRecoverable(){
        service.hide(9,new HideRequest(1,List.of(2L,3L),Map.of(1L,0L,2L,0L,3L,0L),"동일"));
        assertEquals(0,service.groups("",false,0,20).totalElements());
        assertEquals(1,service.groups("",true,0,20).totalElements());
        assertEquals(3,service.facilities("같은 이름").size());
    }
    @Test void hiddenFacilityRemainsFindableAfterReviewedNameChange(){
        service.hide(9,new HideRequest(1,List.of(2L),Map.of(1L,0L,2L,0L),"동일"));
        db.update("UPDATE toilet SET name='변경된 이름' WHERE toilet_id=2");
        assertEquals(1,service.groups("변경된 이름",true,0,20).totalElements());
        assertEquals(0,service.groups("변경된 이름",false,0,20).totalElements());
        service.restore(9,2,new RestoreRequest(1,"새 이름 확인 후 해제"));
        assertEquals("VISIBLE",status(2));
    }
    @Test void missingCoordinatesAndAddressesRemainMissingAfterHide(){
        service.hide(9,new HideRequest(1,List.of(2L),Map.of(1L,0L,2L,0L),"문서 비교 근거"));
        var hidden=service.facilities("같은 이름").get(1);
        assertNull(hidden.latitude());assertNull(hidden.longitude());
        assertNull(hidden.roadAddress());assertNull(hidden.jibunAddress());
        assertEquals("HIDDEN_DUPLICATE",hidden.visibilityStatus());
    }
    @Test void repeatedHideAndRestoreDoNotDuplicateHistoryAndFreshVersionCanRetry(){
        var request=new HideRequest(1,List.of(2L),Map.of(1L,0L,2L,0L),"첫 비교");
        service.hide(9,request);
        assertThrows(ResponseStatusException.class,()->service.hide(9,request));
        assertEquals(1,service.history(2).size());
        assertThrows(ResponseStatusException.class,()->service.restore(9,2,new RestoreRequest(0,"오래된 화면")));
        service.restore(9,2,new RestoreRequest(1,"해제"));
        assertThrows(ResponseStatusException.class,()->service.restore(9,2,new RestoreRequest(1,"중복 클릭")));
        assertEquals(2,service.history(2).size());
        service.hide(9,new HideRequest(1,List.of(2L),Map.of(1L,0L,2L,2L),"최신 조회 후 재처리"));
        assertEquals(3,service.history(2).size());assertEquals("HIDDEN_DUPLICATE",status(2));
    }
    @Test void invalidTargetsAndReasonsLeaveNoPartialChanges(){
        assertThrows(ResponseStatusException.class,()->service.hide(9,new HideRequest(1,List.of(999L),Map.of(1L,0L,999L,0L),"근거")));
        assertThrows(IllegalArgumentException.class,()->service.hide(9,new HideRequest(1,List.of(2L,2L),Map.of(1L,0L,2L,0L),"근거")));
        assertThrows(IllegalArgumentException.class,()->service.hide(9,new HideRequest(1,List.of(2L),Map.of(1L,0L,2L,0L),"  ")));
        assertThrows(IllegalArgumentException.class,()->service.hide(9,new HideRequest(1,List.of(2L),Map.of(1L,0L,2L,0L),"가".repeat(501))));
        assertEquals("VISIBLE",status(2));assertTrue(service.history(2).isEmpty());
    }
    @Test void emptySearchAndInvalidRangesAreHandled(){
        assertTrue(service.groups("없는 시설",false,0,20).items().isEmpty());
        assertEquals(0,service.groups("없는 시설",true,0,20).totalElements());
        assertTrue(service.facilities("없는 시설").isEmpty());
        assertThrows(IllegalArgumentException.class,()->service.groups("",false,-1,20));
        assertThrows(IllegalArgumentException.class,()->service.groups("",false,0,101));
        assertThrows(IllegalArgumentException.class,()->service.facilities(" "));
    }
    @Test void transactionalFailureRollsBackEarlierTargetsAndTheirHistory(){
        db.execute("ALTER TABLE toilet_visibility_event ADD CONSTRAINT synthetic_failure CHECK(toilet_id<>3)");
        var tx=new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->tx.execute(status->
            service.hide(9,new HideRequest(1,List.of(2L,3L),Map.of(1L,0L,2L,0L,3L,0L),"합성 실패 시험"))));
        assertEquals("VISIBLE",status(2));assertEquals("VISIBLE",status(3));
        assertTrue(service.history(2).isEmpty());assertTrue(service.history(3).isEmpty());
    }
    String status(long id){return db.queryForObject("SELECT visibility_status FROM toilet WHERE toilet_id=?",String.class,id);}
}
