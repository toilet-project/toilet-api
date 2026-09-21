package com.example.toiletapi.quality.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.toiletapi.quality.repository.ToiletDisplayGroupRepository;
import com.example.toiletapi.quality.repository.ToiletDisplayGroupRepository.DisplayGroupSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class DisplayGroupTranslationBackfillTest {
    @Test
    void translatesAndStoresExistingGroupsAsOneBatch() {
        ToiletDisplayGroupRepository repository = org.mockito.Mockito.mock(ToiletDisplayGroupRepository.class);
        DisplayGroupTranslator translator = org.mockito.Mockito.mock(DisplayGroupTranslator.class);
        when(repository.findGroupsNeedingEnglishTranslation(100)).thenReturn(List.of(
                new DisplayGroupSource(10L, "XXX문화원"), new DisplayGroupSource(11L, "노은역 광장")));
        when(translator.translate(List.of("XXX문화원", "노은역 광장"))).thenReturn(
                List.of("XXX Cultural Center", "Noeun Station Plaza"));

        new DisplayGroupTranslationBackfill(repository, translator).backfill();

        verify(repository).saveMachineEnglishDisplayName(10L, "XXX문화원", "XXX Cultural Center");
        verify(repository).saveMachineEnglishDisplayName(11L, "노은역 광장", "Noeun Station Plaza");
    }
}
