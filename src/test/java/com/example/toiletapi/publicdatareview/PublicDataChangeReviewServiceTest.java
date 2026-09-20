package com.example.toiletapi.publicdatareview;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.toilet.translation.ToiletTranslationService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import static com.example.toiletapi.publicdatareview.PublicDataChangeReviewModels.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PublicDataChangeReviewServiceTest {
    private JdbcTemplate db;
    private PublicDataChangeReviewService service;
    private AuditLogService audit;
    private ToiletTranslationService translations;
    private String baselineHash;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:public-data-review-api-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(dataSource);
        createSchema();
        db.execute("ALTER TABLE toilet ADD visibility_status VARCHAR(24) DEFAULT 'VISIBLE'");
        db.execute("ALTER TABLE toilet ADD hidden_event_id BIGINT");
        db.execute("ALTER TABLE public_data_change_review ADD baseline_name VARCHAR(100)");
        db.execute("ALTER TABLE public_data_change_review ADD proposal_name VARCHAR(100)");
        db.execute("ALTER TABLE public_data_change_review ADD hidden_event_id BIGINT");
        db.execute("CREATE TABLE toilet_visibility_event(event_id BIGINT PRIMARY KEY,representative_toilet_id BIGINT,reason VARCHAR(500),occurred_at DATETIME)");
        audit = mock(AuditLogService.class);
        translations = mock(ToiletTranslationService.class);
        service = new PublicDataChangeReviewService(new NamedParameterJdbcTemplate(dataSource), audit, translations);
        baselineHash = PublicDataChangeReviewService.hash(new BigDecimal("37.5000000"),
                new BigDecimal("127.1000000"), "서울 도로 1", "서울 지번 1");
        String proposalHash = PublicDataChangeReviewService.hash(new BigDecimal("37.6000000"),
                new BigDecimal("127.2000000"), "서울 도로 2", "서울 지번 2");
        db.update("INSERT INTO app_user(user_id,display_name) VALUES(9,'관리자A')");
        db.update("""
                INSERT INTO toilet(toilet_id,mng_no,name,coordinate_source,latitude,longitude,road_address,jibun_address,data_source,region_revision)
                VALUES(1,'ADMIN-1','테스트 화장실','ADMIN_CONFIRMED',37.5000000,127.1000000,'서울 도로 1','서울 지번 1','PUBLIC_DATA',4)
                """);
        db.update("""
                INSERT INTO public_data_change_review
                    (review_id,toilet_id,active_toilet_id,baseline_latitude,baseline_longitude,baseline_road_address,baseline_jibun_address,
                     proposal_latitude,proposal_longitude,proposal_road_address,proposal_jibun_address,changed_fields,baseline_hash,proposal_hash,
                     provider_updated_at,first_received_at,last_received_at,receipt_count,status,version)
                VALUES(11,1,1,37.5000000,127.1000000,'서울 도로 1','서울 지번 1',37.6000000,127.2000000,
                       '서울 도로 2','서울 지번 2','LATITUDE,LONGITUDE,ROAD_ADDRESS,JIBUN_ADDRESS',?,?,
                       '2026-09-14 18:20:00','2026-09-14 02:00:00','2026-09-15 02:00:00',2,'PENDING',3)
                """, baselineHash, proposalHash);
        db.update("""
                INSERT INTO public_data_confirmed_receipt
                    (execution_key,toilet_id,review_id,received_at,input_hash,protected_before_hash,protected_after_hash,result)
                VALUES('00000000-0000-0000-0000-000000000001',1,11,'2026-09-15 02:00:00',?,?,?,'CHANGE_CANDIDATE')
                """, proposalHash, baselineHash, baselineHash);
    }

    @Test
    void listsAndReturnsLiveComparisonContract() {
        Page page = service.search(Status.PENDING, "테스트", 30, 0, 15, "lastReceivedAt,desc");
        assertEquals(1, page.totalElements());
        assertEquals(1, page.summary().pending());
        assertEquals(1, page.summary().coordinate());

        Detail detail = service.detail(11);
        assertFalse(detail.isStale());
        assertEquals(new BigDecimal("37.5000000"), detail.current().latitude());
        assertEquals(new BigDecimal("37.6000000"), detail.proposal().latitude());
        assertEquals(2, detail.review().receiptCount());
        assertTrue(detail.distanceMeters() > 0);
        assertTrue(detail.validation().issues().isEmpty());
    }

    @Test
    void applyWritesRevisionUpdatesProtectedValuesAndMakesRegionStale() {
        Detail result = service.decide(9, 11,
                new DecisionRequest(Action.APPLY, "공공데이터와 지도 확인", 3L, baselineHash));

        assertEquals(Status.APPLIED, result.review().status());
        assertEquals(new BigDecimal("37.6000000"),
                db.queryForObject("SELECT latitude FROM toilet WHERE toilet_id=1", BigDecimal.class));
        assertEquals("서울 도로 2", db.queryForObject("SELECT road_address FROM toilet WHERE toilet_id=1", String.class));
        assertEquals("ADMIN_CONFIRMED", db.queryForObject("SELECT coordinate_source FROM toilet WHERE toilet_id=1", String.class));
        assertEquals(5L, db.queryForObject("SELECT region_revision FROM toilet WHERE toilet_id=1", Long.class));
        assertEquals("PUBLIC_DATA_REVIEW", db.queryForObject("SELECT source FROM coordinate_revision", String.class));
        assertEquals("APPLY", db.queryForObject("SELECT action FROM public_data_change_decision", String.class));
        verify(audit).record(9L, AuditAction.PUBLIC_DATA_CHANGE_APPLIED,
                "PUBLIC_DATA_CHANGE_REVIEW", 11L, java.util.Map.of("toiletId", 1L, "action", "APPLY"));
        verify(translations).synchronizeKoreanSource(1L);

        Detail retry = service.decide(9, 11,
                new DecisionRequest(Action.APPLY, "공공데이터와 지도 확인", 3L, baselineHash));
        assertEquals(Status.APPLIED, retry.review().status());
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM coordinate_revision", Long.class));
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM public_data_change_decision", Long.class));
    }

    @Test
    void staleVersionOrChangedCurrentValueCannotApply() {
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.decide(9, 11,
                new DecisionRequest(Action.APPLY, "확인", 2L, baselineHash))).getStatusCode().value());

        db.update("UPDATE toilet SET road_address='다른 관리 작업의 주소' WHERE toilet_id=1");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.decide(9, 11,
                new DecisionRequest(Action.APPLY, "확인", 3L, baselineHash))).getStatusCode().value());
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM coordinate_revision", Long.class));
    }

    @Test
    void keepCurrentClosesCandidateWhileDeferKeepsItPending() {
        Detail deferred = service.decide(9, 11, new DecisionRequest(Action.DEFER, "", 3L, baselineHash));
        assertEquals(Status.PENDING, deferred.review().status());
        assertEquals(4L, deferred.review().version());

        Detail kept = service.decide(9, 11,
                new DecisionRequest(Action.KEEP_CURRENT, "거리뷰에서 기존 위치 확인", 4L, baselineHash));
        assertEquals(Status.KEPT_CURRENT, kept.review().status());
        assertEquals(2, kept.decisionHistory().size());
        assertEquals(new BigDecimal("37.5000000"),
                db.queryForObject("SELECT latitude FROM toilet WHERE toilet_id=1", BigDecimal.class));
    }

    @Test
    void hashMatchesBatchContract() {
        assertEquals("482cf0a2de03f7b8290bbd66278a00c8deab5acfbdfacb1ee29f0949f7288fc4",
                PublicDataChangeReviewService.hash(new BigDecimal("37.5"), new BigDecimal("127.1"),
                        " 서울  도로 1 ", "서울 지번 1"));
    }

    @Test
    void hiddenSourceChangeShowsOriginalReasonAndApplyNeverUnhides() {
        String named=PublicDataChangeReviewService.hashNamed("테스트 화장실",new BigDecimal("37.5000000"),new BigDecimal("127.1000000"),"서울 도로 1","서울 지번 1");
        db.update("INSERT INTO toilet_visibility_event VALUES(7,2,'출입구와 주소 동일 확인','2026-09-16 12:00:00')");
        db.update("UPDATE toilet SET visibility_status='HIDDEN_DUPLICATE',hidden_event_id=7 WHERE toilet_id=1");
        db.update("UPDATE public_data_change_review SET baseline_name='테스트 화장실',proposal_name='변경된 화장실',hidden_event_id=7,baseline_hash=?,changed_fields='NAME,ROAD_ADDRESS' WHERE review_id=11",named);
        var detail=service.detail(11);
        assertEquals("출입구와 주소 동일 확인",detail.hiddenContext().reason());
        assertFalse(detail.isStale());
        service.decide(9,11,new DecisionRequest(Action.APPLY,"새 명칭 확인",3L,named));
        assertEquals("변경된 화장실",db.queryForObject("SELECT name FROM toilet WHERE toilet_id=1",String.class));
        assertEquals("HIDDEN_DUPLICATE",db.queryForObject("SELECT visibility_status FROM toilet WHERE toilet_id=1",String.class));
    }

    @Test
    void restoringVisibilityInvalidatesOldHiddenCandidateButRetainsEvidence() {
        db.update("INSERT INTO toilet_visibility_event VALUES(7,2,'같은 시설 확인','2026-09-16 12:00:00')");
        db.update("UPDATE public_data_change_review SET hidden_event_id=7,baseline_name='테스트 화장실',proposal_name='새 이름' WHERE review_id=11");
        assertTrue(service.detail(11).isStale());
        assertEquals("같은 시설 확인",service.detail(11).hiddenContext().reason());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.decide(9,11,new DecisionRequest(Action.KEEP_CURRENT,"확인",3L,baselineHash))).getStatusCode().value());
    }

    private void createSchema() {
        db.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY,display_name VARCHAR(100))");
        db.execute("""
                CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY,mng_no VARCHAR(50),name VARCHAR(100),coordinate_source VARCHAR(30),
                latitude DECIMAL(10,7),longitude DECIMAL(10,7),road_address VARCHAR(255),jibun_address VARCHAR(255),
                data_source VARCHAR(20),region_revision BIGINT)
                """);
        db.execute("""
                CREATE TABLE public_data_change_review(
                review_id BIGINT PRIMARY KEY,toilet_id BIGINT,active_toilet_id BIGINT UNIQUE,
                baseline_latitude DECIMAL(10,7),baseline_longitude DECIMAL(10,7),baseline_road_address VARCHAR(255),baseline_jibun_address VARCHAR(255),
                proposal_latitude DECIMAL(10,7),proposal_longitude DECIMAL(10,7),proposal_road_address VARCHAR(255),proposal_jibun_address VARCHAR(255),
                changed_fields VARCHAR(100),baseline_hash CHAR(64),proposal_hash CHAR(64),provider_updated_at DATETIME,
                first_received_at DATETIME,last_received_at DATETIME,receipt_count INT,status VARCHAR(20),status_reason VARCHAR(500),
                version BIGINT,superseded_by_review_id BIGINT,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)
                """);
        db.execute("""
                CREATE TABLE public_data_confirmed_receipt(receipt_id BIGINT AUTO_INCREMENT PRIMARY KEY,execution_key CHAR(36),toilet_id BIGINT,
                review_id BIGINT,received_at DATETIME,input_hash CHAR(64),protected_before_hash CHAR(64),protected_after_hash CHAR(64),result VARCHAR(30))
                """);
        db.execute("""
                CREATE TABLE public_data_change_decision(decision_id BIGINT AUTO_INCREMENT PRIMARY KEY,review_id BIGINT,action VARCHAR(20),
                note VARCHAR(500),decided_by_user_id BIGINT,decided_at DATETIME,candidate_version BIGINT)
                """);
        db.execute("""
                CREATE TABLE coordinate_revision(coordinate_revision_id BIGINT AUTO_INCREMENT PRIMARY KEY,toilet_id BIGINT,report_id BIGINT,
                previous_latitude DECIMAL(10,7),previous_longitude DECIMAL(10,7),applied_latitude DECIMAL(10,7),applied_longitude DECIMAL(10,7),
                previous_road_address VARCHAR(255),previous_jibun_address VARCHAR(255),applied_road_address VARCHAR(255),applied_jibun_address VARCHAR(255),
                applied_by_user_id BIGINT,applied_at DATETIME,source VARCHAR(30))
                """);
    }
}
