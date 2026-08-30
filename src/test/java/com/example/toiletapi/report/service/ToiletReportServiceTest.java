package com.example.toiletapi.report.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.report.dto.CreateToiletReportRequest;
import com.example.toiletapi.report.model.ToiletReport;
import com.example.toiletapi.report.repository.CoordinateRevisionRepository;
import com.example.toiletapi.report.repository.ToiletReportRepository;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToiletReportServiceTest {

    @Mock private ToiletReportRepository reportRepository;
    @Mock private CoordinateRevisionRepository revisionRepository;
    @Mock private ToiletRepository toiletRepository;
    @Mock private AppUserRepository userRepository;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private ToiletReportService service;

    @Test
    void shouldStoreCoordinateReportWithRoadAddress() {
        when(toiletRepository.findById(10L)).thenReturn(Optional.of(mock(Toilet.class)));
        when(userRepository.findById(3L)).thenReturn(Optional.of(mock(AppUser.class)));
        when(reportRepository.existsByActiveRequestKey(any())).thenReturn(false);
        when(reportRepository.save(any(ToiletReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.submit(3L, new CreateToiletReportRequest(10L, "COORDINATE_CORRECTION", new BigDecimal("36.3500000"), new BigDecimal("127.3800000"), "대전광역시 서구 둔산대로 100", null, "출입구 위치가 다릅니다."));

        ArgumentCaptor<ToiletReport> captor = ArgumentCaptor.forClass(ToiletReport.class);
        verify(reportRepository).save(captor.capture());
        assertEquals("COORDINATE_CORRECTION", captor.getValue().getReportType());
        assertEquals("대전광역시 서구 둔산대로 100", captor.getValue().getProposedRoadAddress());
    }

    @Test
    void shouldStoreOpenTimeReportSeparatelyFromCoordinates() {
        when(toiletRepository.findById(10L)).thenReturn(Optional.of(mock(Toilet.class)));
        when(userRepository.findById(3L)).thenReturn(Optional.of(mock(AppUser.class)));
        when(reportRepository.existsByActiveRequestKey(any())).thenReturn(false);
        when(reportRepository.save(any(ToiletReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.submit(3L, new CreateToiletReportRequest(10L, "OPEN_TIME_CORRECTION", null, null, null, "09:00 ~ 18:00", "현장 안내문을 확인했습니다."));

        ArgumentCaptor<ToiletReport> captor = ArgumentCaptor.forClass(ToiletReport.class);
        verify(reportRepository).save(captor.capture());
        assertEquals("OPEN_TIME_CORRECTION", captor.getValue().getReportType());
        assertEquals("09:00 ~ 18:00", captor.getValue().getProposedOpenTime());
    }

    @Test
    void shouldRejectLocationReportWithoutConfirmedRoadAddress() {
        assertThrows(IllegalArgumentException.class, () -> service.submit(3L,
                new CreateToiletReportRequest(10L, "COORDINATE_CORRECTION", new BigDecimal("36.3500000"), new BigDecimal("127.3800000"), "", null, "주소가 없습니다.")));

        verifyNoInteractions(toiletRepository, userRepository, reportRepository);
    }
}
