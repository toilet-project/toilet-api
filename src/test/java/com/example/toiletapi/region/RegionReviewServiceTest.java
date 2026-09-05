package com.example.toiletapi.region;

import com.example.toiletapi.quality.service.CoordinateQualityService;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.toiletapi.region.RegionReviewModels.*;

class RegionReviewServiceTest {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ToiletRepository toilets = mock(ToiletRepository.class);
    CoordinateQualityService corrections = mock(CoordinateQualityService.class);
    Toilet toilet = mock(Toilet.class);
    RegionReviewService service = new RegionReviewService(jdbc, toilets, corrections);
    @BeforeEach void setup() { when(toilets.findByIdForUpdate(1L)).thenReturn(Optional.of(toilet)); }

    @Test void missingCoordinatesCanBeSetWithUnchangedNullSnapshot() {
        var request = new Correction(BigDecimal.ONE, BigDecimal.TEN, "관리자 현장 확인", new Location(null,null,null,null));
        service.correct(9,1,request);
        verify(corrections).correctToilet(eq(9L),eq(1L),argThat(r -> r.latitude().equals(BigDecimal.ONE) && r.roadAddress() == null));
        verifyNoInteractions(jdbc);
    }
    @Test void concurrentlyChangedCoordinateIsRejectedBeforeAnyGeocodingOrWrite() {
        when(toilet.getLatitude()).thenReturn(BigDecimal.ONE);
        var error = assertThrows(ResponseStatusException.class, () -> service.correct(9,1,
                new Correction(BigDecimal.TEN,BigDecimal.TEN,"확인",new Location(null,null,null,null))));
        assertEquals(409,error.getStatusCode().value());
        verifyNoInteractions(corrections);
    }
    @Test void concurrentlyChangedAddressIsRejected() {
        when(toilet.getJibunAddress()).thenReturn("새 지번");
        assertThrows(ResponseStatusException.class, () -> service.correct(9,1,
                new Correction(BigDecimal.ONE,BigDecimal.TEN,"확인",new Location(null,null,null,"이전 지번"))));
        verifyNoInteractions(corrections);
    }
    @Test void decimalScaleDoesNotCauseFalseConflict() {
        assertTrue(RegionReviewService.same(new BigDecimal("37.5000000"),new BigDecimal("37.5")));
        assertFalse(RegionReviewService.same(null,BigDecimal.ZERO));
    }
    @Test void invalidPaginationAndOversizedKeywordsAreRejectedBeforeSql() {
        assertThrows(IllegalArgumentException.class, () -> service.search(Filter.REVIEW,"",-1,20));
        assertThrows(IllegalArgumentException.class, () -> service.search(Filter.REVIEW,"",0,101));
        assertThrows(IllegalArgumentException.class, () -> service.search(Filter.REVIEW,"a".repeat(101),0,20));
        assertThrows(IllegalArgumentException.class, () -> service.history(1,-1,10));
        verifyNoInteractions(jdbc);
    }
}
