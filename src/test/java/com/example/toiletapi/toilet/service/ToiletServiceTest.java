package com.example.toiletapi.toilet.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToiletServiceTest {

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

        assertTrue(toiletService.getToiletsInBounds(southLat, northLat, westLng, eastLng, 3).markers().isEmpty());

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
                        3
                )
        );

        verifyNoInteractions(toiletRepository);
    }
}
