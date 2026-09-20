package com.example.toiletapi.toilet.translation;

import static com.example.toiletapi.toilet.translation.ToiletTranslationModels.*;

import com.example.toiletapi.global.time.KoreanTime;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ToiletTranslationService {
    private static final String KOREAN = "ko";
    private final ToiletTranslationRepository repository;

    public ToiletTranslationService(ToiletTranslationRepository repository) {
        this.repository = repository;
    }

    public ResolvedText resolve(long toiletId, String requestedLocale) {
        String locale = normalizeLocale(requestedLocale);
        if (!KOREAN.equals(locale)) {
            var translated = repository.find(toiletId, locale, false);
            if (translated.isPresent() && translated.get().current())
                return new ResolvedText(translated.get(), locale, false);
        }
        Text korean = repository.find(toiletId, KOREAN, false).orElseThrow(
                () -> new IllegalArgumentException("표시할 화장실 정보를 찾지 못했습니다."));
        return new ResolvedText(korean, locale, !KOREAN.equals(locale));
    }

    @Transactional
    public boolean saveMachineTranslation(TranslationInput raw) {
        TranslationInput input = validate(raw, false);
        String currentHash = repository.currentSourceHash(input.toiletId(), true);
        if (!Objects.equals(currentHash, input.expectedSourceHash()))
            throw new IllegalStateException("원문이 변경되었습니다. 최신 한국어 값을 기준으로 다시 번역해 주세요.");
        var existing = repository.find(input.toiletId(), input.locale(), true);
        if (existing.isPresent() && existing.get().manualOverride()) return false;
        LocalDateTime now = KoreanTime.now();
        if (existing.isEmpty()) repository.insert(input, "MACHINE_TRANSLATED", false, now, null, now);
        else repository.update(input, "MACHINE_TRANSLATED", false, now, null, now);
        return true;
    }

    @Transactional
    public void saveReviewedTranslation(TranslationInput raw) {
        TranslationInput input = validate(raw, true);
        String currentHash = repository.currentSourceHash(input.toiletId(), true);
        if (!Objects.equals(currentHash, input.expectedSourceHash()))
            throw new IllegalStateException("원문이 변경되었습니다. 최신 한국어 값을 확인한 뒤 검수해 주세요.");
        var existing = repository.find(input.toiletId(), input.locale(), true);
        LocalDateTime now = KoreanTime.now();
        if (existing.isEmpty()) repository.insert(input, "REVIEWED", true, null, now, now);
        else repository.update(input, "REVIEWED", true, existing.get().translatedAt(), now, now);
    }

    public KoreanAudit auditKoreanRows() {
        return repository.auditKoreanRows();
    }

    @Transactional
    public void synchronizeKoreanSource(long toiletId) {
        repository.synchronizeKoreanSource(toiletId, KoreanTime.now());
    }

    static String normalizeLocale(String value) {
        if (value == null || value.isBlank()) return KOREAN;
        String normalized = value.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        Locale parsed = Locale.forLanguageTag(normalized);
        if (parsed.getLanguage().isBlank() || normalized.length() > 12 || !parsed.toLanguageTag().equalsIgnoreCase(normalized))
            throw new IllegalArgumentException("지원할 수 없는 언어 코드입니다.");
        return normalized;
    }

    private static TranslationInput validate(TranslationInput raw, boolean manual) {
        if (raw == null) throw new IllegalArgumentException("번역 정보가 필요합니다.");
        String locale = normalizeLocale(raw.locale());
        if (KOREAN.equals(locale)) throw new IllegalArgumentException("한국어 원본 행은 번역 저장 경로로 수정할 수 없습니다.");
        String name = clean(raw.name(), 255, "화장실명", true);
        String road = clean(raw.roadAddress(), 500, "도로명 주소", false);
        String jibun = clean(raw.jibunAddress(), 500, "지번 주소", false);
        String openTime = clean(raw.openTime(), 255, "개방시간", false);
        String openTimeDetail = clean(raw.openTimeDetail(), 500, "상세 개방시간", false);
        String hash = clean(raw.expectedSourceHash(), 64, "원문 해시", true);
        if (!hash.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("원문 해시 형식이 올바르지 않습니다.");
        String source = manual ? "MANUAL" : clean(raw.source(), 40, "번역 출처", true);
        return new TranslationInput(raw.toiletId(), locale, name, road, jibun, openTime, openTimeDetail,
                hash.toLowerCase(Locale.ROOT), source);
    }

    private static String clean(String value, int max, String label, boolean required) {
        String cleaned = value == null ? null : value.trim();
        if (required && (cleaned == null || cleaned.isEmpty())) throw new IllegalArgumentException(label + "이(가) 필요합니다.");
        if (cleaned != null && cleaned.length() > max) throw new IllegalArgumentException(label + "이(가) 너무 깁니다.");
        return cleaned == null || cleaned.isEmpty() ? null : cleaned;
    }
}
