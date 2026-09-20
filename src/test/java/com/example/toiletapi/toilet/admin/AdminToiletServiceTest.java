package com.example.toiletapi.toilet.admin;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.model.ToiletEditableData;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import com.example.toiletapi.toilet.translation.ToiletTranslationService;
import com.example.toiletapi.toilet.openinghours.OpeningHoursService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.web.server.ResponseStatusException;

import static com.example.toiletapi.toilet.admin.AdminToiletModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminToiletServiceTest {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ToiletRepository toilets = mock(ToiletRepository.class);
    AuditLogService audit = mock(AuditLogService.class);
    ToiletTranslationService translations = mock(ToiletTranslationService.class);
    OpeningHoursService openingHours = mock(OpeningHoursService.class);
    AdminToiletService service = new AdminToiletService(jdbc, toilets, audit, translations, openingHours);
    Toilet toilet = mock(Toilet.class);

    @BeforeEach void setup() {
        when(toilet.getId()).thenReturn(1L);
        when(toilet.getName()).thenReturn("기존 화장실");
        when(toilet.getVisibilityStatus()).thenReturn("VISIBLE");
        when(toilet.getRegionRevision()).thenReturn(1L);
        when(toilet.getMaleToiletCount()).thenReturn(null);
        when(toilet.getMaleUrinalCount()).thenReturn(null);
        when(toilet.getMaleDisabledToiletCount()).thenReturn(null);
        when(toilet.getMaleDisabledUrinalCount()).thenReturn(null);
        when(toilet.getMaleChildToiletCount()).thenReturn(null);
        when(toilet.getMaleChildUrinalCount()).thenReturn(null);
        when(toilet.getFemaleToiletCount()).thenReturn(null);
        when(toilet.getFemaleDisabledToiletCount()).thenReturn(null);
        when(toilet.getFemaleChildToiletCount()).thenReturn(null);
        when(toilets.findByIdForUpdate(1L)).thenReturn(Optional.of(toilet));
        when(toilets.findCurrentRegion(1L)).thenReturn(Optional.empty());
    }

    @Test void searchUsesPartialNameAndCanonicalRegionFilters() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        doReturn(List.of()).when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));

        service.search(" 대학 ", "11", "11230", 0, 15);

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var params = org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertTrue(sql.getValue().contains("t.name"));
        assertTrue(sql.getValue().contains("current_toilet_region"));
        assertTrue(sql.getValue().contains("r.sido_code=:sidoCode"));
        assertTrue(sql.getValue().contains("r.sigungu_code=:sigunguCode"));
        assertEquals("%대학%", params.getValue().getValue("keyword"));
    }

    @Test void searchAndSuggestionBoundsAreValidatedBeforeSql() {
        assertThrows(IllegalArgumentException.class, () -> service.search("", "", "11230", 0, 15));
        assertThrows(IllegalArgumentException.class, () -> service.search("", "1", "", 0, 15));
        assertThrows(IllegalArgumentException.class, () -> service.search("", "", "", -1, 15));
        assertThrows(IllegalArgumentException.class, () -> service.suggestions("대학", 21));
        verifyNoInteractions(jdbc);
    }

    @Test void staleSnapshotIsRejectedBeforeWrite() {
        Editable editable = emptyEditable("변경 화장실");
        var error = assertThrows(ResponseStatusException.class,
                () -> service.update(9, 1, new UpdateRequest("a".repeat(64), editable)));
        assertEquals(409, error.getStatusCode().value());
        verify(toilet, never()).applyAdminUpdate(any());
        verifyNoInteractions(audit);
    }

    @Test void validUpdateWritesChangedFieldsAndAudit() {
        ToiletEditableData before = new ToiletEditableData("기존 화장실", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        String token = AdminToiletService.snapshotToken(before);

        service.update(9, 1, new UpdateRequest(token, emptyEditable("변경 화장실")));

        verify(toilet).applyAdminUpdate(argThat(value -> value.name().equals("변경 화장실")));
        verify(toilets).flush();
        verify(translations).synchronizeKoreanSource(1L);
        verify(audit).record(eq(9L), eq(AuditAction.TOILET_ADMIN_UPDATED), eq("TOILET"), eq(1L),
                argThat(details -> ((List<?>) details.get("changedFields")).contains("name")));
    }

    @Test void unchangedUpdateDoesNotCreateAuditNoise() {
        ToiletEditableData before = new ToiletEditableData("기존 화장실", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        service.update(9, 1, new UpdateRequest(AdminToiletService.snapshotToken(before),
                emptyEditable("기존 화장실")));
        verify(toilet, never()).applyAdminUpdate(any());
        verifyNoInteractions(translations);
        verifyNoInteractions(audit);
    }

    @Test void openingHoursUpdateSynchronizesLanguageNeutralSchedule() {
        ToiletEditableData before = new ToiletEditableData("기존 화장실", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        Editable after = new Editable("기존 화장실", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, "정시", "평일 09:00~18:00",
                null, null, null, null, null, null);

        service.update(9, 1, new UpdateRequest(AdminToiletService.snapshotToken(before), after));

        verify(openingHours).synchronize(1L, "정시", "평일 09:00~18:00");
        verifyNoInteractions(translations);
    }

    private static Editable emptyEditable(String name) {
        return new Editable(name, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
