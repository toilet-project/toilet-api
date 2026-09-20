package com.example.toiletapi.toilet.openinghours;

import static com.example.toiletapi.toilet.openinghours.OpeningHoursModels.BackfillResult;
import static com.example.toiletapi.toilet.openinghours.OpeningHoursModels.ConfirmRequest;
import static com.example.toiletapi.toilet.openinghours.OpeningHoursModels.Normalized;
import static com.example.toiletapi.toilet.openinghours.OpeningHoursModels.PatternApplyResult;
import static com.example.toiletapi.toilet.openinghours.OpeningHoursModels.PatternDetail;
import static com.example.toiletapi.toilet.openinghours.OpeningHoursModels.PatternItem;
import static com.example.toiletapi.toilet.openinghours.OpeningHoursModels.PatternPage;
import static com.example.toiletapi.toilet.openinghours.OpeningHoursModels.Slot;
import static com.example.toiletapi.toilet.openinghours.OpeningHoursModels.View;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.global.exception.ToiletNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpeningHoursService {
    private final OpeningHoursParser parser;
    private final OpeningHoursRepository repository;
    private final AuditLogService audit;
    private static final Set<String> POLICIES = Set.of("ALWAYS", "SCHEDULED", "IRREGULAR", "CLOSED");
    private static final Set<String> HOLIDAY_POLICIES = Set.of("OPEN", "CLOSED", "UNKNOWN");

    public OpeningHoursService(OpeningHoursParser parser, OpeningHoursRepository repository, AuditLogService audit) {
        this.parser = parser;
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional
    public void synchronize(long toiletId, String openTime, String openTimeDetail) {
        String sourceHash = sourceHash(openTime, openTimeDetail);
        var current = repository.currentState(toiletId);
        if (current.isPresent()) {
            var state = current.get();
            if (state.manualOverride()) {
                if (!state.sourceHash().equals(sourceHash)) repository.markManualSourceChanged(toiletId, sourceHash);
                return;
            }
            if (state.sourceHash().equals(sourceHash)
                    && OpeningHoursParser.VERSION.equals(state.parserVersion())) return;
        }
        repository.saveAutomatic(toiletId, sourceHash, parser.parse(openTime, openTimeDetail));
    }

    @Transactional(readOnly = true)
    public Optional<View> find(long toiletId) {
        return repository.find(toiletId);
    }

    @Transactional(readOnly = true)
    public PatternPage patterns(String status, String keyword, int page, int size) {
        if (page < 0) throw new IllegalArgumentException("페이지는 0 이상이어야 합니다.");
        if (size < 1 || size > 100) throw new IllegalArgumentException("한 페이지에 1~100개 유형까지 조회할 수 있습니다.");
        String filter = status == null ? "REVIEW" : status.toUpperCase(Locale.ROOT);
        if (!Set.of("REVIEW", "SOURCE_CHANGED", "PARSED", "CONFIRMED", "ALL").contains(filter)) {
            throw new IllegalArgumentException("지원하지 않는 개방시간 유형 상태입니다.");
        }
        String search = clean(keyword).toLowerCase(Locale.ROOT);
        List<PatternItem> filtered = repository.patterns().stream().map(this::patternItem)
                .filter(item -> search.isEmpty() || clean(item.openTime()).toLowerCase(Locale.ROOT).contains(search)
                        || clean(item.openTimeDetail()).toLowerCase(Locale.ROOT).contains(search)
                        || clean(item.sampleName()).toLowerCase(Locale.ROOT).contains(search))
                .filter(item -> switch (filter) {
                    case "REVIEW" -> "REVIEW_REQUIRED".equals(item.status()) || "SOURCE_CHANGED".equals(item.status());
                    case "ALL" -> true;
                    default -> filter.equals(item.status());
                }).toList();
        int from = Math.min(page * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        int totalPages = filtered.isEmpty() ? 0 : (filtered.size() + size - 1) / size;
        return new PatternPage(filtered.subList(from, to), page, size, filtered.size(), totalPages);
    }

    @Transactional(readOnly = true)
    public Optional<PatternDetail> patternDetail(String patternKey) {
        return findPattern(patternKey).map(row -> new PatternDetail(patternItem(row),
                repository.patternMembers(row.openTime(), row.openTimeDetail(), 30)));
    }

    @Transactional
    public PatternApplyResult confirmPattern(long adminId, String patternKey, ConfirmRequest request) {
        var pattern = findPattern(patternKey).orElseThrow(() -> new IllegalArgumentException("개방시간 유형을 찾지 못했습니다."));
        Normalized confirmed = validateConfirmation(request);
        var targets = repository.patternTargets(pattern.openTime(), pattern.openTimeDetail());
        for (var source : targets) {
            repository.saveManual(adminId, source.toiletId(), sourceHash(source.openTime(), source.openTimeDetail()), confirmed);
        }
        audit.record(adminId, AuditAction.TOILET_OPENING_HOURS_CONFIRMED, "TOILET_OPENING_HOURS_PATTERN", null,
                Map.of("patternKey", patternKey, "appliedCount", targets.size(),
                        "protectedCount", pattern.protectedCount(), "openingPolicy", confirmed.openingPolicy(),
                        "open24h", confirmed.open24h(), "scheduleCount", confirmed.schedules().size(),
                        "holidayPolicy", confirmed.holidayPolicy()));
        return new PatternApplyResult(patternKey, targets.size(), pattern.protectedCount());
    }

    private Optional<OpeningHoursRepository.PatternRow> findPattern(String patternKey) {
        if (patternKey == null || !patternKey.matches("[a-f0-9]{64}")) return Optional.empty();
        return repository.patterns().stream()
                .filter(row -> sourceHash(row.openTime(), row.openTimeDetail()).equals(patternKey)).findFirst();
    }

    private PatternItem patternItem(OpeningHoursRepository.PatternRow row) {
        Normalized suggested = parser.parse(row.openTime(), row.openTimeDetail());
        String status = row.sourceChangedCount() > 0 ? "SOURCE_CHANGED"
                : row.targetCount() == 0 ? "CONFIRMED" : suggested.status();
        return new PatternItem(sourceHash(row.openTime(), row.openTimeDetail()), row.openTime(), row.openTimeDetail(),
                row.facilityCount(), row.targetCount(), row.protectedCount(), row.sampleName(), status, suggested);
    }

    @Transactional
    public BackfillResult normalizeAfter(long afterId, int limit) {
        if (afterId < 0) throw new IllegalArgumentException("시작 식별자는 0 이상이어야 합니다.");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("한 번에 1~1000건까지 정형화할 수 있습니다.");
        var sources = repository.sourcesAfter(afterId, limit + 1);
        boolean hasMore = sources.size() > limit;
        var selected = hasMore ? sources.subList(0, limit) : sources;
        selected.forEach(source -> synchronize(source.toiletId(), source.openTime(), source.openTimeDetail()));
        long lastId = selected.isEmpty() ? afterId : selected.getLast().toiletId();
        return new BackfillResult(lastId, selected.size(), hasMore);
    }

    @Transactional
    public View confirm(long adminId, long toiletId, ConfirmRequest request) {
        var source = repository.source(toiletId).orElseThrow(() -> new ToiletNotFoundException(toiletId));
        Normalized confirmed = validateConfirmation(request);
        repository.saveManual(adminId, toiletId, sourceHash(source.openTime(), source.openTimeDetail()), confirmed);
        audit.record(adminId, AuditAction.TOILET_OPENING_HOURS_CONFIRMED, "TOILET", toiletId,
                Map.of("openingPolicy", confirmed.openingPolicy(), "open24h", confirmed.open24h(),
                        "scheduleCount", confirmed.schedules().size(), "holidayPolicy", confirmed.holidayPolicy()));
        return repository.find(toiletId).orElseThrow(() -> new IllegalStateException("확정한 개방시간을 조회할 수 없습니다."));
    }

    private static Normalized validateConfirmation(ConfirmRequest request) {
        if (request == null || request.openingPolicy() == null || !POLICIES.contains(request.openingPolicy())) {
            throw new IllegalArgumentException("확정할 개방 정책이 올바르지 않습니다.");
        }
        if (request.open24h() == null) throw new IllegalArgumentException("24시간 운영 여부를 선택해 주세요.");
        String holidayPolicy = request.holidayPolicy() == null ? "UNKNOWN" : request.holidayPolicy();
        if (!HOLIDAY_POLICIES.contains(holidayPolicy)) throw new IllegalArgumentException("공휴일 운영 정책이 올바르지 않습니다.");
        List<Slot> slots = request.schedules().stream().map(value -> {
            if (value.dayOfWeek() < 1 || value.dayOfWeek() > 7 || value.slotIndex() < 0 || value.slotIndex() > 20) {
                throw new IllegalArgumentException("요일 또는 시간대 순서가 올바르지 않습니다.");
            }
            if (value.closed()) {
                if (value.startTime() != null || value.endTime() != null) {
                    throw new IllegalArgumentException("휴무 요일에는 시작·종료 시간을 입력할 수 없습니다.");
                }
            } else {
                if (value.startTime() == null || value.endTime() == null || value.startTime().equals(value.endTime())) {
                    throw new IllegalArgumentException("운영 시간의 시작·종료 시각을 확인해 주세요.");
                }
                boolean expectedOvernight = !value.endTime().isAfter(value.startTime());
                if (expectedOvernight != value.crossesMidnight()) {
                    throw new IllegalArgumentException("익일 종료 여부와 시작·종료 시각이 일치하지 않습니다.");
                }
            }
            return new Slot(value.dayOfWeek(), value.slotIndex(), value.startTime(), value.endTime(),
                    value.crossesMidnight(), value.closed());
        }).toList();
        Set<String> keys = new HashSet<>();
        if (slots.stream().anyMatch(slot -> !keys.add(slot.dayOfWeek() + ":" + slot.slotIndex()))) {
            throw new IllegalArgumentException("같은 요일과 시간대 순서가 중복되었습니다.");
        }
        if (request.open24h() && (!"ALWAYS".equals(request.openingPolicy()) || !slots.isEmpty())) {
            throw new IllegalArgumentException("24시간 운영은 ALWAYS 정책이며 별도 시간표를 갖지 않습니다.");
        }
        if (!request.open24h() && "SCHEDULED".equals(request.openingPolicy()) && slots.isEmpty()) {
            throw new IllegalArgumentException("시간제 운영에는 하나 이상의 요일별 시간표가 필요합니다.");
        }
        if (("CLOSED".equals(request.openingPolicy()) || "IRREGULAR".equals(request.openingPolicy()))
                && !slots.isEmpty()) {
            throw new IllegalArgumentException("해당 개방 정책에는 요일별 시간표를 저장할 수 없습니다.");
        }
        return new Normalized(request.openingPolicy(), request.open24h(), "CONFIRMED", 1.0,
                holidayPolicy, slots);
    }

    static String sourceHash(String openTime, String openTimeDetail) {
        String source = clean(openTime) + '\u001f' + clean(openTimeDetail);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("개방시간 원문 해시를 만들 수 없습니다.", exception);
        }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
