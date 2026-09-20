package com.example.toiletapi.toilet.openinghours;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.time.LocalTime;
import java.util.Optional;
import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpeningHoursServiceTest {
    @Mock OpeningHoursParser parser;
    @Mock OpeningHoursRepository repository;
    @Mock AuditLogService audit;
    @InjectMocks OpeningHoursService service;

    @Test
    void automaticRowsAreReplacedWithCurrentParseResult() {
        var parsed = new OpeningHoursModels.Normalized("ALWAYS", true, "PARSED", 1.0, "UNKNOWN", List.of());
        when(repository.currentState(7L)).thenReturn(Optional.empty());
        when(parser.parse("정시", "24시간")).thenReturn(parsed);

        service.synchronize(7L, "정시", "24시간");

        verify(repository).saveAutomatic(eq(7L), eq(OpeningHoursService.sourceHash("정시", "24시간")), eq(parsed));
    }

    @Test
    void sourceChangesNeverOverwriteManualConfirmation() {
        when(repository.currentState(7L)).thenReturn(Optional.of(
                new OpeningHoursRepository.CurrentState("old-hash", true, "v1")));

        service.synchronize(7L, "정시", "평일 09:00~18:00");

        verify(repository).markManualSourceChanged(7L,
                OpeningHoursService.sourceHash("정시", "평일 09:00~18:00"));
        verify(parser, never()).parse(any(), any());
        verify(repository, never()).saveAutomatic(eq(7L), any(), any());
    }

    @Test
    void backfillUsesStableCursorAndReportsNextPage() {
        when(repository.sourcesAfter(10L, 3)).thenReturn(List.of(
                new OpeningHoursRepository.RawSource(11L, "정시", "24시간"),
                new OpeningHoursRepository.RawSource(12L, "정시", "09:00~18:00"),
                new OpeningHoursRepository.RawSource(13L, "상시", null)));
        when(repository.currentState(11L)).thenReturn(Optional.of(
                new OpeningHoursRepository.CurrentState("old-11", true, "v1")));
        when(repository.currentState(12L)).thenReturn(Optional.of(
                new OpeningHoursRepository.CurrentState("old-12", true, "v1")));

        var result = service.normalizeAfter(10L, 2);

        assertEquals(12L, result.lastToiletId());
        assertEquals(2, result.normalizedCount());
        assertEquals(true, result.hasMore());
    }

    @Test
    void unchangedSourceAtCurrentParserVersionIsSkipped() {
        String hash = OpeningHoursService.sourceHash("정시", "24시간");
        when(repository.currentState(7L)).thenReturn(Optional.of(
                new OpeningHoursRepository.CurrentState(hash, false, OpeningHoursParser.VERSION)));

        service.synchronize(7L, "정시", "24시간");

        verify(parser, never()).parse(any(), any());
        verify(repository, never()).saveAutomatic(eq(7L), any(), any());
    }

    @Test
    void unchangedSourceIsReparsedAfterParserUpgrade() {
        String hash = OpeningHoursService.sourceHash("정시", "24시간");
        var parsed = new OpeningHoursModels.Normalized("ALWAYS", true, "PARSED", 1.0, "UNKNOWN", List.of());
        when(repository.currentState(7L)).thenReturn(Optional.of(
                new OpeningHoursRepository.CurrentState(hash, false, "bootstrap-v1")));
        when(parser.parse("정시", "24시간")).thenReturn(parsed);

        service.synchronize(7L, "정시", "24시간");

        verify(repository).saveAutomatic(7L, hash, parsed);
    }

    @Test
    void administratorCanConfirmWeeklyScheduleWithoutLocalizedText() {
        var request = new OpeningHoursModels.ConfirmRequest("SCHEDULED", false, "CLOSED", List.of(
                new OpeningHoursModels.ScheduleInput(1, 0, LocalTime.of(9, 0), LocalTime.of(18, 0), false, false)));
        var view = new OpeningHoursModels.View("SCHEDULED", false, "CONFIRMED", 1.0, "v1", "CLOSED",
                true, false, List.of(new OpeningHoursModels.Slot(1, 0, LocalTime.of(9, 0),
                LocalTime.of(18, 0), false, false)));
        when(repository.source(7L)).thenReturn(Optional.of(
                new OpeningHoursRepository.RawSource(7L, "정시", "월요일 09:00~18:00")));
        when(repository.find(7L)).thenReturn(Optional.of(view));

        var result = service.confirm(9L, 7L, request);

        assertEquals("CONFIRMED", result.status());
        verify(repository).saveManual(eq(9L), eq(7L), any(), any());
        verify(audit).record(eq(9L), eq(AuditAction.TOILET_OPENING_HOURS_CONFIRMED),
                eq("TOILET"), eq(7L), any());
    }

    @Test
    void twentyFourHourConfirmationCannotCarryLimitedSchedule() {
        var request = new OpeningHoursModels.ConfirmRequest("ALWAYS", true, "OPEN", List.of(
                new OpeningHoursModels.ScheduleInput(1, 0, LocalTime.of(9, 0), LocalTime.of(18, 0), false, false)));
        when(repository.source(7L)).thenReturn(Optional.of(
                new OpeningHoursRepository.RawSource(7L, "상시", "")));
        assertThrows(IllegalArgumentException.class, () -> service.confirm(9L, 7L, request));
    }
}
