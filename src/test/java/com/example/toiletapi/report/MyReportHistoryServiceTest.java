package com.example.toiletapi.report;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.example.toiletapi.report.dto.ReportStatusCount;
import com.example.toiletapi.report.model.ReportStatus;
import com.example.toiletapi.report.repository.ToiletReportRepository;
import com.example.toiletapi.report.service.ToiletReportService;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MyReportHistoryServiceTest {
    @Mock ToiletReportRepository reportRepository;
    @Mock ToiletRepository toiletRepository;
    @InjectMocks ToiletReportService service;

    @Test void pageKeepsDateCountsAndUsesBoundedStableOrderWithoutLegacyRead() {
        var from = LocalDateTime.of(2026, 10, 4, 0, 0);
        var to = from.plusDays(1);
        when(reportRepository.countOwnerHistory(3L, from, to)).thenReturn(List.of(
                new ReportStatusCount(ReportStatus.PENDING, 23), new ReportStatusCount(ReportStatus.APPROVED, 7)));
        when(reportRepository.findOwnerHistory(eq(3L), eq(ReportStatus.PENDING), eq(from), eq(to), any())).thenReturn(List.of());
        var result = service.minePage(3L, ReportStatus.PENDING, from.toLocalDate(), from.toLocalDate(), 1, 10);
        assertThat(result.totalElements()).isEqualTo(23);
        assertThat(result.statusCounts()).containsEntry("ALL", 30L).containsEntry("APPROVED", 7L).containsEntry("CANCELLED", 0L);
        assertThat(result.hasNext()).isTrue();
        var page = ArgumentCaptor.forClass(Pageable.class);
        verify(reportRepository).findOwnerHistory(eq(3L), eq(ReportStatus.PENDING), eq(from), eq(to), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(10);
        assertThat(page.getValue().getOffset()).isEqualTo(10);
        assertThat(page.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
        verify(reportRepository, never()).findByReporterUserIdOrderByCreatedAtDesc(any());
    }
    @Test void rejectsInvalidDateAndPageBeforeReading() {
        for (int[] page : new int[][]{{-1, 10}, {0, 0}, {0, 51}, {Integer.MAX_VALUE, 50}})
            assertThatThrownBy(() -> service.minePage(3L, null, null, null, page[0], page[1])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.minePage(3L, null, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 4), 0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.minePage(null, null, null, null, 0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.mineDetail(null, 99L)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(reportRepository, toiletRepository);
    }
    @Test void unavailableDetailUsesSameNotFoundForMissingAndOtherOwners() {
        when(reportRepository.findByIdAndReporterUserId(99L, 3L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.mineDetail(3L, 99L)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404));
        verifyNoInteractions(toiletRepository);
        verify(reportRepository, never()).findById(any());
    }
}
