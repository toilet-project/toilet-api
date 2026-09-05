package com.example.toiletapi.toilet.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.toiletapi.global.exception.ToiletNotFoundException;
import com.example.toiletapi.toilet.dto.ToiletDetailResponse;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import com.example.toiletapi.toilet.repository.ToiletRegionProjection;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToiletServiceTest {

    @Test
    void shouldExposeOnlyCurrentVerifiedRegion() {
        Toilet toilet = mock(Toilet.class);
        ToiletRegionProjection region = mock(ToiletRegionProjection.class);
        when(toiletRepository.findById(101L)).thenReturn(Optional.of(toilet));
        when(toiletRepository.findCurrentRegion(101L)).thenReturn(Optional.of(region));
        when(region.getSidoName()).thenReturn("충청남도");
        when(region.getSidoCode()).thenReturn("44");
        when(region.getSigunguName()).thenReturn("천안시 서북구");
        when(region.getSigunguCode()).thenReturn("44133");
        when(region.getCityName()).thenReturn("천안시");
        when(region.getDistrictName()).thenReturn("서북구");

        var result = toiletService.getToiletDetail(101L).region();
        assertEquals("44", result.sidoCode());
        assertEquals("천안시 서북구", result.sigunguName());
        assertEquals("44133", result.sigunguCode());
        assertEquals("천안시", result.cityName());
        assertEquals("서북구", result.districtName());
    }

    @Test
    void shouldNotInventRegionWhenCurrentViewHasNoMatch() {
        Toilet toilet = mock(Toilet.class);
        when(toiletRepository.findById(102L)).thenReturn(Optional.of(toilet));
        when(toiletRepository.findCurrentRegion(102L)).thenReturn(Optional.empty());
        when(toilet.getRoadAddress()).thenReturn("대전광역시 유성구 대학로 99");
        var result = toiletService.getToiletDetail(102L);
        assertNull(result.region());
        assertEquals("대전광역시 유성구 대학로 99", result.roadAddress());
    }

    @Mock
    private ToiletRepository toiletRepository;

    @InjectMocks
    private ToiletService toiletService;

    @Test
    void shouldQueryRepositoryWithRequestedBounds() {
        BigDecimal southLat = new BigDecimal("37.4900");
        BigDecimal northLat = new BigDecimal("37.5100");
        BigDecimal westLng = new BigDecimal("127.0100");
        BigDecimal eastLng = new BigDecimal("127.0300");
        when(toiletRepository.findByLatitudeBetweenAndLongitudeBetween(
                southLat, northLat, westLng, eastLng
        )).thenReturn(List.of());

        assertTrue(toiletService.getToiletsInBounds(southLat, northLat, westLng, eastLng, 3, false).toilets().isEmpty());

        verify(toiletRepository).findByLatitudeBetweenAndLongitudeBetween(
                southLat, northLat, westLng, eastLng
        );
    }

    @Test
    void shouldRejectReversedBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> toiletService.getToiletsInBounds(
                        new BigDecimal("37.5100"),
                        new BigDecimal("37.4900"),
                        new BigDecimal("127.0100"),
                        new BigDecimal("127.0300"),
                        3,
                        false
                )
        );

        verifyNoInteractions(toiletRepository);
    }

    @Test
    void shouldReturnMappedToiletDetail() {
        Toilet toilet = org.mockito.Mockito.mock(Toilet.class);
        when(toilet.getId()).thenReturn(101L);
        when(toilet.getName()).thenReturn("강남역 공중화장실");
        when(toilet.getToiletType()).thenReturn("공중화장실");
        when(toilet.getRoadAddress()).thenReturn("서울특별시 강남구 강남대로 396");
        when(toilet.getLatitude()).thenReturn(new java.math.BigDecimal("37.4979"));
        when(toilet.getLongitude()).thenReturn(new java.math.BigDecimal("127.0276"));
        when(toilet.getMaleToiletCount()).thenReturn(3);
        when(toilet.getFemaleToiletCount()).thenReturn(6);
        when(toilet.getOpenTime()).thenReturn("24시간");
        when(toilet.getHasEmergencyBell()).thenReturn("Y");
        when(toilet.getDataSource()).thenReturn("PUBLIC_DATA");
        when(toiletRepository.findById(101L)).thenReturn(Optional.of(toilet));

        ToiletDetailResponse response = toiletService.getToiletDetail(101L);

        assertEquals(101L, response.id());
        assertEquals("강남역 공중화장실", response.name());
        assertEquals("공중화장실", response.toiletType());
        assertEquals("서울특별시 강남구 강남대로 396", response.roadAddress());
        assertEquals(new java.math.BigDecimal("37.4979"), response.latitude());
        assertEquals(new java.math.BigDecimal("127.0276"), response.longitude());
        assertEquals(3, response.maleToiletCount());
        assertEquals(6, response.femaleToiletCount());
        assertEquals("24시간", response.openTime());
        assertEquals("Y", response.hasEmergencyBell());
        assertEquals("PUBLIC_DATA", response.dataSource());
        verify(toiletRepository).findById(101L);
    }

    @Test
    void shouldThrowNotFoundWhenToiletDoesNotExist() {
        when(toiletRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ToiletNotFoundException.class, () -> toiletService.getToiletDetail(999L));

        verify(toiletRepository).findById(999L);
    }
}
