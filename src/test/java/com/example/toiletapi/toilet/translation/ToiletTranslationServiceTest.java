package com.example.toiletapi.toilet.translation;

import static com.example.toiletapi.toilet.translation.ToiletTranslationModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ToiletTranslationServiceTest {
    private final ToiletTranslationRepository repository = mock(ToiletTranslationRepository.class);
    private final ToiletTranslationService service = new ToiletTranslationService(repository);
    private final String hash = "a".repeat(64);

    @Test void staleOrMissingEnglishFallsBackToKoreanWithoutClaimingTranslation() {
        Text staleEnglish = text("en", "Old name", "b".repeat(64), false, false);
        Text korean = text("ko", "한국어 이름", hash, true, false);
        when(repository.find(1, "en", false)).thenReturn(Optional.of(staleEnglish));
        when(repository.find(1, "ko", false)).thenReturn(Optional.of(korean));

        ResolvedText resolved = service.resolve(1, "EN");

        assertTrue(resolved.fallbackToKorean());
        assertEquals("한국어 이름", resolved.text().name());
        assertEquals("en", resolved.requestedLocale());
    }

    @Test void currentEnglishIsReturned() {
        Text english = text("en", "Restroom", hash, true, false);
        when(repository.find(1, "en", false)).thenReturn(Optional.of(english));

        ResolvedText resolved = service.resolve(1, "en");

        assertFalse(resolved.fallbackToKorean());
        assertEquals("Restroom", resolved.text().name());
        verify(repository, never()).find(1, "ko", false);
    }

    @Test void currentTranslationsAreGroupedForBulkPublicReads() {
        Text english = text("en", "Restroom", hash, true, false);
        Text japanese = new Text(2, "ja", "トイレ", null, null, hash,
                "REVIEWED", "MANUAL", true, 1, null, LocalDateTime.now(), true);
        when(repository.findCurrentTranslations(List.of(1L, 2L))).thenReturn(List.of(english, japanese));

        Map<Long, Map<String, Text>> result = service.currentTranslations(List.of(1L, 2L));

        assertEquals("Restroom", result.get(1L).get("en").name());
        assertEquals("トイレ", result.get(2L).get("ja").name());
    }

    @Test void machineTranslationCannotOverwriteReviewedManualText() {
        TranslationInput input = input("en", hash, "GOOGLE_CLOUD");
        when(repository.currentSourceHash(1, true)).thenReturn(hash);
        when(repository.find(1, "en", true)).thenReturn(Optional.of(text("en", "Reviewed", hash, true, true)));

        assertFalse(service.saveMachineTranslation(input));
        verify(repository, never()).update(any(), anyString(), anyBoolean(), any(), any(), any());
    }

    @Test void changedSourceRejectsMachineTranslation() {
        when(repository.currentSourceHash(1, true)).thenReturn("b".repeat(64));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.saveMachineTranslation(input("en", hash, "GOOGLE_CLOUD")));
        assertTrue(error.getMessage().contains("원문이 변경"));
        verify(repository, never()).insert(any(), anyString(), anyBoolean(), any(), any(), any());
    }

    @Test void reviewedTranslationBecomesProtectedManualValue() {
        TranslationInput input = input("en", hash, "ignored");
        when(repository.currentSourceHash(1, true)).thenReturn(hash);
        when(repository.find(1, "en", true)).thenReturn(Optional.empty());

        service.saveReviewedTranslation(input);

        verify(repository).insert(argThat(value -> "MANUAL".equals(value.source())), eq("REVIEWED"), eq(true),
                isNull(), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test void localeAndLengthsAreValidatedBeforeStorage() {
        assertEquals("ko", ToiletTranslationService.normalizeLocale(null));
        assertEquals("en-us", ToiletTranslationService.normalizeLocale("en_US"));
        assertThrows(IllegalArgumentException.class, () -> ToiletTranslationService.normalizeLocale("not_a_locale_value"));
        assertThrows(IllegalArgumentException.class, () -> service.saveMachineTranslation(input("ko", hash, "SOURCE")));
        assertThrows(IllegalArgumentException.class, () -> service.saveMachineTranslation(
                new TranslationInput(1, "en", "x".repeat(256), null, null, hash, "TEST")));
    }

    private TranslationInput input(String locale, String sourceHash, String source) {
        return new TranslationInput(1, locale, "Restroom", "110 Sejong-daero", "31 Taepyeong-ro",
                sourceHash, source);
    }

    private Text text(String locale, String name, String sourceHash, boolean current, boolean manual) {
        return new Text(1, locale, name, null, null, sourceHash,
                manual ? "REVIEWED" : "MACHINE_TRANSLATED", manual ? "MANUAL" : "GOOGLE_CLOUD",
                manual, 1, null, null, current);
    }
}
