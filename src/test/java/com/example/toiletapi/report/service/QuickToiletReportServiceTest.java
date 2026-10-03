package com.example.toiletapi.report.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.geocoding.*;
import com.example.toiletapi.notification.service.UserNotificationService;
import com.example.toiletapi.report.dto.*;
import com.example.toiletapi.report.model.*;
import com.example.toiletapi.report.repository.*;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import com.example.toiletapi.toilet.translation.ToiletTranslationService;
import com.example.toiletapi.toilet.openinghours.OpeningHoursService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class QuickToiletReportServiceTest {
    final ToiletReportRepository reports = mock(ToiletReportRepository.class);
    final ToiletRepository toilets = mock(ToiletRepository.class);
    final AppUserRepository users = mock(AppUserRepository.class);
    final CoordinateAddressResolver resolver = mock(CoordinateAddressResolver.class);
    final ToiletTranslationService translations = mock(ToiletTranslationService.class);
    final OpeningHoursService hours = mock(OpeningHoursService.class);
    final ToiletReportService service = new ToiletReportService(reports, mock(CoordinateRevisionRepository.class), toilets, users,
            mock(AuditLogService.class), mock(UserNotificationService.class), resolver, translations, hours);
    final String guest = UUID.randomUUID().toString(), key = UUID.randomUUID().toString();
    final BigDecimal lat = new BigDecimal("36.3"), lng = new BigDecimal("127.3");
    Toilet facility;
    @BeforeEach void setup() {
        facility = Toilet.fromApprovedReport("공개 화장실", lat, lng, "현재 주소", null);
        ReflectionTestUtils.setField(facility, "id", 10L); facility.applyReportedOpenTime("09:00~18:00");
        when(toilets.findById(10L)).thenReturn(Optional.of(facility));
        when(toilets.findByIdForUpdate(10L)).thenReturn(Optional.of(facility));
        when(reports.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }
    QuickToiletReportRequest observation(String type) { return new QuickToiletReportRequest(10L, type, null, null, null, null, null); }
    @Test void observationsSnapshotServerDataWithoutChangingFacility() {
        ReflectionTestUtils.setField(facility, "openTimeDetail", "평일 09:00~18:00 / 주말 10:00~16:00");
        ReflectionTestUtils.setField(facility, "jibunAddress", "접수 당시 지번주소");
        for (String type : List.of("FACILITY_MISSING", "TEMPORARILY_CLOSED")) {
            var result = service.submitQuick(null, guest, UUID.randomUUID().toString(), observation(type));
            assertThat(result.reporterKind()).isEqualTo("GUEST"); assertThat(result.observedAt()).isNotNull();
            assertThat(result.latitude()).isEqualTo(lat); assertThat(result.openTime()).isEqualTo("09:00~18:00");
            assertThat(result.reason()).isEmpty(); assertThat(result.status()).isEqualTo("PENDING");
            assertThat(result.openTimeDetail()).isEqualTo("평일 09:00~18:00 / 주말 10:00~16:00");
            assertThat(result.jibunAddress()).isEqualTo("접수 당시 지번주소");
        }
        verify(toilets, never()).save(any()); verifyNoInteractions(resolver, translations, hours, users);
    }
    @Test void newFacilityRemainsOnlyAReportUntilAdminApproval() {
        var result = service.submitQuick(null, guest, key, new QuickToiletReportRequest(null, "NEW_FACILITY", lat, lng, "입력 주소", "새 화장실", null));
        assertThat(result.toiletId()).isNull(); assertThat(result.toiletName()).isEqualTo("새 화장실");
        verifyNoInteractions(toilets, resolver, translations);
    }
    @Test void idempotentReplaySurvivesCompletionAndChangedPayloadIsRejected() {
        service.submitQuick(null, guest, key, observation("FACILITY_MISSING"));
        var saved = ArgumentCaptor.forClass(ToiletReport.class); verify(reports).save(saved.capture());
        saved.getValue().approve(3L, "확인 완료");
        when(reports.findBySubmissionKey(saved.getValue().getSubmissionKey())).thenReturn(Optional.of(saved.getValue()));
        assertThat(service.submitQuick(null, guest, key, observation("FACILITY_MISSING")).status()).isEqualTo("APPROVED");
        assertThatThrownBy(() -> service.submitQuick(null, guest, key, observation("TEMPORARILY_CLOSED"))).isInstanceOf(IllegalArgumentException.class);
        verify(reports, times(1)).save(any());
    }
    @Test void guestIsNotDisplayedAsAWithdrawnMember() {
        var report = ToiletReport.quick(null, null, "NEW_FACILITY", "새 시설", lat, lng, "", null, "", "a", "b", "c");
        when(reports.findById(1L)).thenReturn(Optional.of(report));
        var detail = service.pendingDetail(1L);
        assertThat(detail.reporterDisplayName()).isEqualTo("비회원"); assertThat(detail.toilet().id()).isNull();
        verifyNoInteractions(users);
    }
    @Test void memberOwnershipCannotBeProvidedByTheRequest() {
        when(users.findById(7L)).thenReturn(Optional.of(mock(AppUser.class)));
        service.submitQuick(7L, null, key, observation("FACILITY_MISSING"));
        var saved = ArgumentCaptor.forClass(ToiletReport.class); verify(reports).save(saved.capture());
        assertThat(saved.getValue().getReporterUserId()).isEqualTo(7L); assertThat(saved.getValue().getReporterKind()).isEqualTo("MEMBER");
    }
    @Test void observationsApproveWithoutHidingOrChangingHours() {
        var report = ToiletReport.quick(10L, null, "TEMPORARILY_CLOSED", facility.getName(), lat, lng, "", "09:00~18:00", "", "a", "b", "c");
        when(reports.findByIdForUpdate(1L)).thenReturn(Optional.of(report));
        service.approve(3L, 1L, null);
        assertThat(facility.isPubliclyVisible()).isTrue(); assertThat(facility.getOpenTime()).isEqualTo("09:00~18:00");
        verifyNoInteractions(resolver, translations, hours);
    }
    @Test void newApprovalCreatesAndLinksOneFacility() {
        var report = ToiletReport.quick(null, null, "NEW_FACILITY", "새 화장실", lat, lng, "제안 주소", null, "", "a", "b", "c");
        when(reports.findByIdForUpdate(1L)).thenReturn(Optional.of(report));
        when(resolver.resolve(lat, lng)).thenReturn(new CoordinateAddress(lat, lng, "검증된 주소", null));
        when(toilets.saveAndFlush(any())).thenAnswer(call -> { Toilet created = call.getArgument(0); ReflectionTestUtils.setField(created, "id", 99L); return created; });
        assertThat(service.approve(3L, 1L, null).toiletId()).isEqualTo(99L);
        assertThatThrownBy(() -> service.approve(3L, 1L, null)).isInstanceOf(IllegalArgumentException.class);
        verify(toilets, times(1)).saveAndFlush(any()); verify(translations).synchronizeKoreanSource(99L);
    }
    @Test void invalidNamesCoordinatesTargetsAndDuplicatePendingAreRejected() {
        assertThatThrownBy(() -> service.submitQuick(null, guest, key, new QuickToiletReportRequest(null, "NEW_FACILITY", lat, lng, "", " ", null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.submitQuick(null, guest, key, new QuickToiletReportRequest(null, "NEW_FACILITY", BigDecimal.ZERO, lng, "", "이름", null))).isInstanceOf(IllegalArgumentException.class);
        when(reports.existsByActiveRequestKey(any())).thenReturn(true);
        assertThatThrownBy(() -> service.submitQuick(null, guest, key, observation("FACILITY_MISSING"))).isInstanceOf(IllegalArgumentException.class);
    }
}
