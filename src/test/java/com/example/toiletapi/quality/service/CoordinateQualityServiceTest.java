package com.example.toiletapi.quality.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.geocoding.CoordinateAddress;
import com.example.toiletapi.geocoding.CoordinateAddressResolver;
import com.example.toiletapi.geocoding.AddressLookupException;
import com.example.toiletapi.quality.dto.CorrectToiletCoordinateRequest;
import com.example.toiletapi.quality.dto.CreateMapDisplayGroupRequest;
import com.example.toiletapi.quality.dto.DuplicateCoordinateGroupResponse;
import com.example.toiletapi.quality.dto.SaveToiletDisplayGroupRequest;
import com.example.toiletapi.quality.model.CoordinateQualityStatus;
import com.example.toiletapi.quality.repository.CoordinateQualityReviewRepository;
import com.example.toiletapi.quality.repository.ToiletDisplayGroupRepository;
import com.example.toiletapi.report.model.CoordinateRevision;
import com.example.toiletapi.report.repository.CoordinateRevisionRepository;
import com.example.toiletapi.report.repository.ToiletReportRepository;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import com.example.toiletapi.toilet.translation.ToiletTranslationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

@ExtendWith(MockitoExtension.class)
class CoordinateQualityServiceTest {
    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock CoordinateQualityReviewRepository reviewRepository;
    @Mock ToiletRepository toiletRepository;
    @Mock ToiletTranslationService translations;
    @Mock ToiletReportRepository reportRepository;
    @Mock CoordinateRevisionRepository revisionRepository;
    @Mock AuditLogService auditLogService;
    @Mock CoordinateAddressResolver addressResolver;
    @Mock ToiletDisplayGroupRepository displayGroupRepository;
    @Mock DisplayGroupTranslator displayGroupTranslator;
    @Mock Toilet toilet;
    private CoordinateQualityService service;

    @BeforeEach
    void setUp() {
        service = new CoordinateQualityService(jdbc, reviewRepository, toiletRepository, translations, reportRepository,
                revisionRepository, auditLogService, addressResolver, displayGroupRepository, displayGroupTranslator);
    }

    @Test
    void searchesEveryVisibleUngroupedToiletNameByPartialKeyword() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<DuplicateCoordinateGroupResponse>>any()))
                .thenReturn(List.of());

        service.search("  충남   대학  ", null, 0, 20);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<DuplicateCoordinateGroupResponse>>any());
        MapSqlParameterSource values = (MapSqlParameterSource) parameters.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("충남 대학", values.getValue("keyword"));
        org.junit.jupiter.api.Assertions.assertEquals("%충남%대학%", values.getValue("keywordPattern"));
        org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("COALESCE(t.name, '') LIKE :keywordPattern"));
        org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("g.group_id IS NULL"));
        org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("d.keyword_match = 1"));
        org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("COUNT(*) OVER() AS total_elements"));
        verify(jdbc, never()).queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class));
    }

    @Test
    void returnsWindowCountWithoutRepeatingGroupAggregation() throws Exception {
        var rs = mock(java.sql.ResultSet.class);
        when(rs.getString(anyString())).thenAnswer(call -> "review_status".equals(call.getArgument(0)) ? "PENDING" : "test");
        when(rs.getLong(anyString())).thenAnswer(call -> "total_elements".equals(call.getArgument(0)) ? 41L : 2L);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(call -> List.of(((RowMapper<?>) call.getArgument(2)).mapRow(rs, 0)));
        var result = service.search("대학", null, 1, 20);
        org.junit.jupiter.api.Assertions.assertEquals(41, result.totalElements());
        org.junit.jupiter.api.Assertions.assertEquals(3, result.totalPages());
        org.junit.jupiter.api.Assertions.assertEquals(1, result.items().size());
        verify(jdbc, never()).queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class));
    }

    @Test
    void emptyFirstPageDoesNotRunAnotherCountQuery() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        var result = service.search("없는검색어", null, 0, 20);
        org.junit.jupiter.api.Assertions.assertEquals(0, result.totalElements());
        org.junit.jupiter.api.Assertions.assertEquals(0, result.totalPages());
        verify(jdbc, never()).queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class));
    }

    @Test
    void emptyLaterPageCountsRemainingGroupsSoClientCanClampPage() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(19L);
        var result = service.search("대학", CoordinateQualityStatus.PENDING, 2, 20);
        org.junit.jupiter.api.Assertions.assertEquals(19, result.totalElements());
        org.junit.jupiter.api.Assertions.assertEquals(1, result.totalPages());
        org.junit.jupiter.api.Assertions.assertTrue(result.items().isEmpty());
    }

    @Test
    void correctToiletRecordsRevisionAndProtectsCoordinateAsAdminConfirmed() {
        Long adminId = 7L;
        Long toiletId = 101L;
        BigDecimal latitude = new BigDecimal("36.3663613");
        BigDecimal longitude = new BigDecimal("127.3148032");
        when(toiletRepository.findByIdForUpdate(toiletId)).thenReturn(Optional.of(toilet));
        when(toilet.getId()).thenReturn(toiletId);
        when(toilet.getLatitude()).thenReturn(new BigDecimal("36.3660000"), latitude);
        when(toilet.getLongitude()).thenReturn(new BigDecimal("127.3140000"), longitude);
        when(toilet.getRoadAddress()).thenReturn("기존 주소", "대전광역시 유성구 노은로 101");
        when(toilet.getCoordinateSource()).thenReturn("ADMIN_CONFIRMED");
        when(addressResolver.resolve(latitude, longitude)).thenReturn(new CoordinateAddress(latitude, longitude, "대전광역시 유성구 노은로 101", "지번 주소"));

        service.correctToilet(adminId, toiletId, new CorrectToiletCoordinateRequest(
                latitude, longitude, "대전광역시 유성구 노은로 101", "관리자 현장 확인"));

        verify(toilet).applyAdminConfirmedCoordinates(latitude, longitude, "대전광역시 유성구 노은로 101", "지번 주소");
        verify(displayGroupRepository).removeToilet(toiletId);
        verify(revisionRepository).save(any(CoordinateRevision.class));
        verify(translations).synchronizeKoreanSource(toiletId);
        verify(auditLogService).record(eq(adminId), eq(AuditAction.TOILET_COORDINATE_CORRECTED),
                eq("TOILET"), eq(toiletId), any(Map.class));
    }

    @Test
    void correctsToMarkerCoordinateAndJoinsAnExistingConfirmedDisplayGroup() {
        Long adminId = 7L;
        Long toiletId = 101L;
        BigDecimal latitude = new BigDecimal("36.3663613");
        BigDecimal longitude = new BigDecimal("127.3148032");
        when(toiletRepository.findByIdForUpdate(toiletId)).thenReturn(Optional.of(toilet));
        when(toilet.getLatitude()).thenReturn(new BigDecimal("36.3660000"), latitude);
        when(toilet.getLongitude()).thenReturn(new BigDecimal("127.3140000"), longitude);
        when(toilet.getRoadAddress()).thenReturn("기존 주소", "대전광역시 유성구 노은로 101");
        when(addressResolver.resolve(latitude, longitude)).thenReturn(new CoordinateAddress(
                latitude, longitude, "대전광역시 유성구 노은로 101", "지번 주소"));
        when(displayGroupRepository.belongsToCoordinates(31L, latitude, longitude)).thenReturn(true);
        when(displayGroupRepository.memberIds(31L)).thenReturn(List.of(202L, 203L));
        when(displayGroupRepository.matchingToiletIds(List.of(202L, 203L, toiletId), latitude, longitude))
                .thenReturn(List.of(toiletId, 202L, 203L));

        service.correctToilet(adminId, toiletId, new CorrectToiletCoordinateRequest(
                latitude, longitude, null, "지도 확인, 거리뷰 확인", 31L));

        verify(toiletRepository).flush();
        verify(displayGroupRepository, never()).removeToilet(toiletId);
        verify(displayGroupRepository).replaceMembers(31L, List.of(202L, 203L, toiletId));
        verify(displayGroupRepository, never()).create(anyString(), any(), any(), any());
        verify(displayGroupRepository, never()).update(any(), anyString(), any());
        verify(auditLogService).record(eq(adminId), eq(AuditAction.TOILET_DISPLAY_GROUP_SAVED),
                eq("TOILET_DISPLAY_GROUP"), eq(31L), any(Map.class));
    }

    @Test
    void createsDisplayGroupByMovingCurrentToiletToMarkerCoordinates() {
        Long adminId = 7L;
        Long toiletId = 101L;
        BigDecimal currentLatitude = new BigDecimal("37.5000000");
        BigDecimal currentLongitude = new BigDecimal("127.1000000");
        BigDecimal latitude = new BigDecimal("37.5454041");
        BigDecimal longitude = new BigDecimal("127.2239403");
        when(toiletRepository.findByIdForUpdate(toiletId)).thenReturn(Optional.of(toilet));
        when(toilet.getLatitude()).thenReturn(currentLatitude);
        when(toilet.getLongitude()).thenReturn(currentLongitude);
        when(toilet.getRoadAddress()).thenReturn("기존 주소");
        when(addressResolver.resolve(latitude, longitude)).thenReturn(new CoordinateAddress(
                latitude, longitude, "경기도 하남시 미사대로 750", "지번 주소"));
        when(displayGroupRepository.matchingToiletIds(List.of(202L, 203L), latitude, longitude))
                .thenReturn(List.of(202L, 203L));
        when(displayGroupRepository.matchingToiletIds(List.of(toiletId, 202L, 203L), latitude, longitude))
                .thenReturn(List.of(toiletId, 202L, 203L));
        when(displayGroupRepository.create("XXX문화원", latitude, longitude, adminId)).thenReturn(41L);
        when(displayGroupTranslator.translate("XXX문화원")).thenReturn("XXX Cultural Center");

        var result = service.createMapDisplayGroup(adminId, toiletId, new CreateMapDisplayGroupRequest(
                CreateMapDisplayGroupRequest.Direction.CURRENT_TO_MARKER, " XXX문화원 ",
                currentLatitude, currentLongitude, latitude, longitude, List.of(202L, 203L), "지도 확인"));

        org.junit.jupiter.api.Assertions.assertEquals(List.of(toiletId, 202L, 203L), result.toiletIds());
        verify(toilet).applyAdminConfirmedCoordinates(latitude, longitude, "경기도 하남시 미사대로 750", "지번 주소");
        verify(toiletRepository).flush();
        verify(displayGroupRepository).create("XXX문화원", latitude, longitude, adminId);
        verify(displayGroupRepository).saveMachineEnglishDisplayName(41L, "XXX문화원", "XXX Cultural Center");
        verify(displayGroupRepository).replaceMembers(41L, List.of(toiletId, 202L, 203L));
        verify(revisionRepository).saveAll(anyList());
        verify(translations).synchronizeKoreanSource(toiletId);
        verify(auditLogService).record(eq(adminId), eq(AuditAction.TOILET_DISPLAY_GROUP_SAVED),
                eq("TOILET_DISPLAY_GROUP"), eq(41L), any(Map.class));
    }

    @Test
    void createsDisplayGroupByMovingMarkerToiletsToCurrentCoordinates() {
        Long adminId = 7L;
        Long currentToiletId = 101L;
        BigDecimal currentLatitude = new BigDecimal("37.5000000");
        BigDecimal currentLongitude = new BigDecimal("127.1000000");
        BigDecimal markerLatitude = new BigDecimal("37.5454041");
        BigDecimal markerLongitude = new BigDecimal("127.2239403");
        Toilet markerToilet1 = mock(Toilet.class);
        Toilet markerToilet2 = mock(Toilet.class);
        when(toiletRepository.findByIdForUpdate(currentToiletId)).thenReturn(Optional.of(toilet));
        when(toiletRepository.findByIdForUpdate(202L)).thenReturn(Optional.of(markerToilet1));
        when(toiletRepository.findByIdForUpdate(203L)).thenReturn(Optional.of(markerToilet2));
        when(toilet.getLatitude()).thenReturn(currentLatitude);
        when(toilet.getLongitude()).thenReturn(currentLongitude);
        when(markerToilet1.getLatitude()).thenReturn(markerLatitude);
        when(markerToilet1.getLongitude()).thenReturn(markerLongitude);
        when(markerToilet2.getLatitude()).thenReturn(markerLatitude);
        when(markerToilet2.getLongitude()).thenReturn(markerLongitude);
        when(addressResolver.resolve(currentLatitude, currentLongitude)).thenReturn(new CoordinateAddress(
                currentLatitude, currentLongitude, "서울특별시 강동구 상일로 10", "지번 주소"));
        when(displayGroupRepository.matchingToiletIds(List.of(202L, 203L), markerLatitude, markerLongitude))
                .thenReturn(List.of(202L, 203L));
        when(displayGroupRepository.matchingToiletIds(
                List.of(currentToiletId, 202L, 203L), currentLatitude, currentLongitude))
                .thenReturn(List.of(currentToiletId, 202L, 203L));
        when(displayGroupRepository.create("XXX문화원", currentLatitude, currentLongitude, adminId)).thenReturn(42L);
        when(displayGroupTranslator.translate("XXX문화원")).thenReturn("XXX Cultural Center");

        var result = service.createMapDisplayGroup(adminId, currentToiletId, new CreateMapDisplayGroupRequest(
                CreateMapDisplayGroupRequest.Direction.MARKERS_TO_CURRENT, "XXX문화원",
                currentLatitude, currentLongitude, markerLatitude, markerLongitude,
                List.of(202L, 203L), "거리뷰 확인"));

        org.junit.jupiter.api.Assertions.assertEquals(List.of(currentToiletId, 202L, 203L), result.toiletIds());
        verify(markerToilet1).applyAdminConfirmedCoordinates(currentLatitude, currentLongitude,
                "서울특별시 강동구 상일로 10", "지번 주소");
        verify(markerToilet2).applyAdminConfirmedCoordinates(currentLatitude, currentLongitude,
                "서울특별시 강동구 상일로 10", "지번 주소");
        verify(toilet, never()).applyAdminConfirmedCoordinates(any(), any(), any(), any());
        verify(displayGroupRepository).replaceMembers(42L, List.of(currentToiletId, 202L, 203L));
        verify(displayGroupRepository).saveMachineEnglishDisplayName(42L, "XXX문화원", "XXX Cultural Center");
        verify(revisionRepository).saveAll(anyList());
        verify(translations).synchronizeKoreanSource(202L);
        verify(translations).synchronizeKoreanSource(203L);
    }

    @Test
    void rejectsMarkerSelectionThatDoesNotMatchClickedCoordinates() {
        Long currentToiletId = 101L;
        BigDecimal currentLatitude = new BigDecimal("37.5000000");
        BigDecimal currentLongitude = new BigDecimal("127.1000000");
        BigDecimal markerLatitude = new BigDecimal("37.5454041");
        BigDecimal markerLongitude = new BigDecimal("127.2239403");
        when(toiletRepository.findByIdForUpdate(currentToiletId)).thenReturn(Optional.of(toilet));
        when(toilet.getLatitude()).thenReturn(currentLatitude);
        when(toilet.getLongitude()).thenReturn(currentLongitude);
        when(addressResolver.resolve(markerLatitude, markerLongitude)).thenReturn(new CoordinateAddress(
                markerLatitude, markerLongitude, "경기도 하남시 미사대로 750", "지번 주소"));
        when(displayGroupRepository.matchingToiletIds(List.of(202L, 203L), markerLatitude, markerLongitude))
                .thenReturn(List.of(202L));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.createMapDisplayGroup(7L, currentToiletId, new CreateMapDisplayGroupRequest(
                        CreateMapDisplayGroupRequest.Direction.CURRENT_TO_MARKER, "XXX문화원",
                        currentLatitude, currentLongitude, markerLatitude, markerLongitude,
                        List.of(202L, 203L), "지도 확인")));

        verify(toilet, never()).applyAdminConfirmedCoordinates(any(), any(), any(), any());
        verify(displayGroupRepository, never()).create(anyString(), any(), any(), any());
        verify(displayGroupRepository, never()).replaceMembers(any(), anyList());
    }

    @Test
    void rejectsJoiningAGroupAtDifferentCoordinates() {
        Long adminId = 7L;
        Long toiletId = 101L;
        BigDecimal latitude = new BigDecimal("36.3663613");
        BigDecimal longitude = new BigDecimal("127.3148032");
        when(toiletRepository.findByIdForUpdate(toiletId)).thenReturn(Optional.of(toilet));
        when(toilet.getLatitude()).thenReturn(new BigDecimal("36.3660000"), latitude);
        when(toilet.getLongitude()).thenReturn(new BigDecimal("127.3140000"), longitude);
        when(toilet.getRoadAddress()).thenReturn("기존 주소", "대전광역시 유성구 노은로 101");
        when(addressResolver.resolve(latitude, longitude)).thenReturn(new CoordinateAddress(
                latitude, longitude, "대전광역시 유성구 노은로 101", "지번 주소"));
        when(displayGroupRepository.belongsToCoordinates(31L, latitude, longitude)).thenReturn(false);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.correctToilet(adminId, toiletId, new CorrectToiletCoordinateRequest(
                        latitude, longitude, null, "지도 확인", 31L)));

        verify(displayGroupRepository, never()).replaceMembers(any(), anyList());
        verify(displayGroupRepository, never()).create(anyString(), any(), any(), any());
        verify(displayGroupRepository, never()).update(any(), anyString(), any());
    }

    @Test
    void lookupFailureDoesNotLockOrChangeToilet() {
        when(addressResolver.resolve(any(), any())).thenThrow(new AddressLookupException());
        org.junit.jupiter.api.Assertions.assertThrows(AddressLookupException.class,
                () -> service.correctToilet(7L, 101L, new CorrectToiletCoordinateRequest(BigDecimal.ONE, BigDecimal.TEN, null, null)));
        org.mockito.Mockito.verifyNoInteractions(toiletRepository, revisionRepository, auditLogService);
    }

    @Test
    void savesSelectedToiletsAsNamedMapGroup() {
        BigDecimal latitude = new BigDecimal("36.4000000");
        BigDecimal longitude = new BigDecimal("127.3000000");
        var coordinateGroup = new DuplicateCoordinateGroupResponse("group-key", latitude, longitude, 3,
                "XXX문화원 1층", "대전광역시", CoordinateQualityStatus.PENDING, 0);
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<DuplicateCoordinateGroupResponse>>any()))
                .thenReturn(List.of(coordinateGroup));
        when(displayGroupRepository.matchingToiletIds(List.of(11L, 12L, 13L), latitude, longitude))
                .thenReturn(List.of(11L, 12L, 13L));
        when(displayGroupRepository.create("XXX문화원", latitude, longitude, 7L)).thenReturn(21L);
        when(displayGroupTranslator.translate("XXX문화원")).thenReturn("XXX Cultural Center");

        var result = service.saveDisplayGroup(7L, "group-key",
                new SaveToiletDisplayGroupRequest(null, " XXX문화원 ", List.of(11L, 12L, 13L)));

        org.junit.jupiter.api.Assertions.assertEquals(21L, result.id());
        org.junit.jupiter.api.Assertions.assertEquals("XXX문화원", result.displayName());
        org.junit.jupiter.api.Assertions.assertEquals("XXX Cultural Center", result.englishDisplayName());
        verify(displayGroupRepository).saveMachineEnglishDisplayName(21L, "XXX문화원", "XXX Cultural Center");
        verify(displayGroupRepository).replaceMembers(21L, List.of(11L, 12L, 13L));
        verify(auditLogService).record(eq(7L), eq(AuditAction.TOILET_DISPLAY_GROUP_SAVED),
                eq("TOILET_DISPLAY_GROUP"), eq(21L), any(Map.class));
    }

    @Test
    void rejectsToiletsOutsideCurrentCoordinateGroup() {
        BigDecimal latitude = new BigDecimal("36.4000000");
        BigDecimal longitude = new BigDecimal("127.3000000");
        var coordinateGroup = new DuplicateCoordinateGroupResponse("group-key", latitude, longitude, 3,
                "XXX문화원 1층", "대전광역시", CoordinateQualityStatus.PENDING, 0);
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<DuplicateCoordinateGroupResponse>>any()))
                .thenReturn(List.of(coordinateGroup));
        when(displayGroupRepository.matchingToiletIds(List.of(11L, 99L), latitude, longitude))
                .thenReturn(List.of(11L));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.saveDisplayGroup(7L, "group-key",
                        new SaveToiletDisplayGroupRequest(null, "XXX문화원", List.of(11L, 99L))));

        verify(displayGroupRepository, never()).create(anyString(), any(), any(), any());
        verify(displayGroupRepository, never()).replaceMembers(any(), any());
    }

    @Test
    void updatesAnExistingMapGroupWithTheNewSelection() {
        BigDecimal latitude = new BigDecimal("36.4000000");
        BigDecimal longitude = new BigDecimal("127.3000000");
        var coordinateGroup = new DuplicateCoordinateGroupResponse("group-key", latitude, longitude, 4,
                "XXX문화원 1층", "대전광역시", CoordinateQualityStatus.PENDING, 0);
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<DuplicateCoordinateGroupResponse>>any()))
                .thenReturn(List.of(coordinateGroup));
        when(displayGroupRepository.matchingToiletIds(List.of(11L, 12L, 13L, 14L), latitude, longitude))
                .thenReturn(List.of(11L, 12L, 13L, 14L));
        when(displayGroupRepository.belongsToCoordinates(21L, latitude, longitude)).thenReturn(true);
        when(displayGroupRepository.findCurrentEnglishDisplayName(21L, "XXX문화원 본관"))
                .thenReturn(Optional.of("XXX Cultural Center Main Building"));

        var result = service.saveDisplayGroup(7L, "group-key",
                new SaveToiletDisplayGroupRequest(21L, "XXX문화원 본관", List.of(11L, 12L, 13L, 14L)));

        org.junit.jupiter.api.Assertions.assertEquals(21L, result.id());
        verify(displayGroupRepository).update(21L, "XXX문화원 본관", 7L);
        verify(displayGroupRepository).saveMachineEnglishDisplayName(
                21L, "XXX문화원 본관", "XXX Cultural Center Main Building");
        verify(displayGroupTranslator, never()).translate(anyString());
        verify(displayGroupRepository).replaceMembers(21L, List.of(11L, 12L, 13L, 14L));
        verify(displayGroupRepository, never()).create(anyString(), any(), any(), any());
    }

    @Test
    void retranslatesWhenAnExistingGroupsKoreanNameChanges() {
        BigDecimal latitude = new BigDecimal("36.4000000");
        BigDecimal longitude = new BigDecimal("127.3000000");
        var coordinateGroup = new DuplicateCoordinateGroupResponse("group-key", latitude, longitude, 2,
                "XXX문화원 1층", "대전광역시", CoordinateQualityStatus.PENDING, 0);
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<DuplicateCoordinateGroupResponse>>any()))
                .thenReturn(List.of(coordinateGroup));
        when(displayGroupRepository.matchingToiletIds(List.of(11L, 12L), latitude, longitude))
                .thenReturn(List.of(11L, 12L));
        when(displayGroupRepository.belongsToCoordinates(21L, latitude, longitude)).thenReturn(true);
        when(displayGroupRepository.findCurrentEnglishDisplayName(21L, "XXX문화원 별관"))
                .thenReturn(Optional.empty());
        when(displayGroupTranslator.translate("XXX문화원 별관"))
                .thenReturn("XXX Cultural Center Annex");

        service.saveDisplayGroup(7L, "group-key",
                new SaveToiletDisplayGroupRequest(21L, "XXX문화원 별관", List.of(11L, 12L)));

        verify(displayGroupTranslator).translate("XXX문화원 별관");
        verify(displayGroupRepository).saveMachineEnglishDisplayName(
                21L, "XXX문화원 별관", "XXX Cultural Center Annex");
    }

}
