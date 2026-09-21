package com.example.toiletapi.toilet.translation;

import java.time.LocalDateTime;

public final class ToiletTranslationModels {
    private ToiletTranslationModels() {}

    public record Text(
            long toiletId,
            String locale,
            String name,
            String roadAddress,
            String jibunAddress,
            String sourceHash,
            String status,
            String source,
            String addressStatus,
            String addressSource,
            boolean manualOverride,
            long version,
            LocalDateTime translatedAt,
            LocalDateTime reviewedAt,
            boolean current
    ) {}

    public record ResolvedText(Text text, String requestedLocale, boolean fallbackToKorean) {}

    public record TranslationInput(
            long toiletId,
            String locale,
            String name,
            String roadAddress,
            String jibunAddress,
            String expectedSourceHash,
            String source
    ) {}

    public record KoreanAudit(long toilets, long koreanRows, long missingRows, long staleKoreanRows) {}
}
