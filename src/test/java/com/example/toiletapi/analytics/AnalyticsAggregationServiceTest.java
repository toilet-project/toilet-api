package com.example.toiletapi.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AnalyticsAggregationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T03:00:00Z");

    @Test
    void refreshesFourteenCompletedKoreanCalendarDaysAndExpiresRawEvents() {
        AnalyticsRepository repository = Mockito.mock(AnalyticsRepository.class);
        when(repository.startRun(any(), any(), any())).thenReturn(17L);
        AnalyticsAggregationService service = new AnalyticsAggregationService(repository,
                Clock.fixed(NOW, ZoneOffset.UTC), true);

        service.aggregateRecentCompletedDays();

        ArgumentCaptor<LocalDate> dates = ArgumentCaptor.forClass(LocalDate.class);
        verify(repository, times(14)).aggregate(dates.capture(), eq(NOW), eq(true));
        assertThat(dates.getAllValues()).startsWith(LocalDate.parse("2026-09-02"))
                .endsWith(LocalDate.parse("2026-09-15"));
        verify(repository).deleteExpiredEvents(LocalDate.parse("2026-08-13"));
        verify(repository).completeRun(eq(17L), eq(NOW), eq(14));
    }

    @Test
    void keepsTodayAvailableForTheAdminWithoutFinalizingIt() {
        AnalyticsRepository repository = Mockito.mock(AnalyticsRepository.class);
        AnalyticsAggregationService service = new AnalyticsAggregationService(repository,
                Clock.fixed(NOW, ZoneOffset.UTC), true);

        service.refreshToday();

        verify(repository).aggregate(LocalDate.parse("2026-09-16"), NOW, false);
    }

    @Test
    void disabledCollectionDoesNotRunBackgroundDatabaseWork() {
        AnalyticsRepository repository = Mockito.mock(AnalyticsRepository.class);
        AnalyticsAggregationService service = new AnalyticsAggregationService(repository,
                Clock.fixed(NOW, ZoneOffset.UTC), false);

        service.aggregateRecentCompletedDays();
        service.refreshToday();

        verify(repository, never()).aggregate(any(), any(), anyBoolean());
    }
}
