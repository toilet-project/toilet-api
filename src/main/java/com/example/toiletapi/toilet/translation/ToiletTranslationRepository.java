package com.example.toiletapi.toilet.translation;

import static com.example.toiletapi.toilet.translation.ToiletTranslationModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ToiletTranslationRepository {
    private static final String SOURCE_HASH_SQL = """
            SHA2(CONCAT(COALESCE(TRIM(t.name), ''), CHAR(31),
                        COALESCE(TRIM(t.road_address), ''), CHAR(31),
                        COALESCE(TRIM(t.jibun_address), '')), 256)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ToiletTranslationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Text> find(long toiletId, String locale, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.query("""
                SELECT tr.toilet_id,tr.locale,tr.name,tr.road_address,tr.jibun_address,
                       tr.source_hash,tr.translation_status,
                       tr.translation_source,tr.address_translation_status,tr.address_translation_source,
                       tr.manual_override,tr.version,tr.translated_at,tr.reviewed_at,
                       CASE WHEN tr.locale='ko' OR tr.source_hash=ko.source_hash THEN TRUE ELSE FALSE END AS is_current
                  FROM toilet_translation tr
                  JOIN toilet_translation ko ON ko.toilet_id=tr.toilet_id AND ko.locale='ko'
                 WHERE tr.toilet_id=:toiletId AND tr.locale=:locale
                """ + suffix,
                new MapSqlParameterSource("toiletId", toiletId).addValue("locale", locale),
                (rs, row) -> map(rs)).stream().findFirst();
    }

    public List<Text> findCurrentTranslations(Collection<Long> toiletIds) {
        if (toiletIds == null || toiletIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT tr.toilet_id,tr.locale,tr.name,tr.road_address,tr.jibun_address,
                       tr.source_hash,tr.translation_status,
                       tr.translation_source,tr.address_translation_status,tr.address_translation_source,
                       tr.manual_override,tr.version,tr.translated_at,tr.reviewed_at,
                       TRUE AS is_current
                  FROM toilet_translation tr
                  JOIN toilet_translation ko ON ko.toilet_id=tr.toilet_id AND ko.locale='ko'
                 WHERE tr.toilet_id IN (:toiletIds)
                   AND tr.locale <> 'ko'
                   AND tr.source_hash = ko.source_hash
                 ORDER BY tr.toilet_id,tr.locale
                """, new MapSqlParameterSource("toiletIds", toiletIds), (rs, row) -> map(rs));
    }

    public record MarkerName(long toiletId, String locale, String name) {}

    public List<MarkerName> findCurrentMarkerNames(Collection<Long> toiletIds) {
        if (toiletIds == null || toiletIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT tr.toilet_id,tr.locale,tr.name
                  FROM toilet_translation tr
                  JOIN toilet_translation ko ON ko.toilet_id=tr.toilet_id AND ko.locale='ko'
                 WHERE tr.toilet_id IN (:toiletIds)
                   AND tr.locale <> 'ko'
                   AND tr.source_hash = ko.source_hash
                 ORDER BY tr.toilet_id,tr.locale
                """, new MapSqlParameterSource("toiletIds", toiletIds),
                (rs, row) -> new MarkerName(rs.getLong("toilet_id"), rs.getString("locale"), rs.getString("name")));
    }

    public String currentSourceHash(long toiletId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.query("""
                SELECT source_hash FROM toilet_translation
                 WHERE toilet_id=:toiletId AND locale='ko'
                """ + suffix, Map.of("toiletId", toiletId),
                (rs, row) -> rs.getString(1)).stream().findFirst().orElseThrow(
                () -> new IllegalArgumentException("한국어 원본 표시값이 없는 화장실입니다."));
    }

    public void synchronizeKoreanSource(long toiletId, LocalDateTime now) {
        Long sourceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toilet WHERE toilet_id=:toiletId", Map.of("toiletId", toiletId), Long.class);
        if (sourceCount == null || sourceCount != 1L)
            throw new IllegalArgumentException("동기화할 화장실을 찾지 못했습니다.");
        String sql = """
                INSERT INTO toilet_translation
                    (toilet_id,locale,name,road_address,jibun_address,
                     source_hash,translation_status,translation_source,
                     address_translation_status,address_translation_source,manual_override,
                     translated_at,reviewed_at,created_at,updated_at)
                SELECT t.toilet_id,'ko',t.name,t.road_address,t.jibun_address,
                       %s,
                       'SOURCE','SOURCE','SOURCE','SOURCE',FALSE,NULL,NULL,:now,:now
                  FROM toilet t WHERE t.toilet_id=:toiletId
                ON DUPLICATE KEY UPDATE
                    version=IF(toilet_translation.source_hash<>VALUES(source_hash),
                               toilet_translation.version+1,toilet_translation.version),
                    updated_at=IF(toilet_translation.source_hash<>VALUES(source_hash),
                                  VALUES(updated_at),toilet_translation.updated_at),
                    name=VALUES(name),road_address=VALUES(road_address),jibun_address=VALUES(jibun_address),
                    source_hash=VALUES(source_hash),translation_status='SOURCE',translation_source='SOURCE',
                    address_translation_status='SOURCE',address_translation_source='SOURCE'
                """.formatted(SOURCE_HASH_SQL);
        jdbc.update(sql, new MapSqlParameterSource("toiletId", toiletId).addValue("now", now));
    }

    public void insert(TranslationInput input, String status, boolean manualOverride,
                       LocalDateTime translatedAt, LocalDateTime reviewedAt, LocalDateTime now) {
        try {
            jdbc.update("""
                    INSERT INTO toilet_translation
                        (toilet_id,locale,name,road_address,jibun_address,
                         source_hash,translation_status,translation_source,
                         address_translation_status,address_translation_source,manual_override,
                         translated_at,reviewed_at,created_at,updated_at)
                    VALUES (:toiletId,:locale,:name,:road,:jibun,
                            :sourceHash,:status,:source,:addressStatus,:addressSource,:manualOverride,
                            :translatedAt,:reviewedAt,:now,:now)
                    """, values(input, status, manualOverride, translatedAt, reviewedAt, now));
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("번역 행이 동시에 생성되었습니다. 새로고침 후 다시 시도해 주세요.", duplicate);
        }
    }

    public void update(TranslationInput input, String status, boolean manualOverride,
                       LocalDateTime translatedAt, LocalDateTime reviewedAt, LocalDateTime now) {
        int changed = jdbc.update("""
                UPDATE toilet_translation
                   SET name=:name,road_address=:road,jibun_address=:jibun,
                       source_hash=:sourceHash,translation_status=:status,translation_source=:source,
                       address_translation_status=:addressStatus,address_translation_source=:addressSource,
                       manual_override=:manualOverride,translated_at=:translatedAt,reviewed_at=:reviewedAt,
                       version=version+1,updated_at=:now
                 WHERE toilet_id=:toiletId AND locale=:locale
                """, values(input, status, manualOverride, translatedAt, reviewedAt, now));
        if (changed != 1) throw new IllegalStateException("수정할 번역 행을 찾지 못했습니다.");
    }

    public KoreanAudit auditKoreanRows() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS toilets,
                       SUM(CASE WHEN ko.toilet_id IS NOT NULL THEN 1 ELSE 0 END) AS korean_rows,
                       SUM(CASE WHEN ko.toilet_id IS NULL THEN 1 ELSE 0 END) AS missing_rows,
                       SUM(CASE WHEN ko.toilet_id IS NOT NULL AND ko.source_hash <> (
                """ + SOURCE_HASH_SQL + """
                       ) THEN 1 ELSE 0 END) AS stale_rows
                  FROM toilet t
                  LEFT JOIN toilet_translation ko ON ko.toilet_id=t.toilet_id AND ko.locale='ko'
                """, Map.of(), (rs, row) -> new KoreanAudit(
                rs.getLong("toilets"), rs.getLong("korean_rows"),
                rs.getLong("missing_rows"), rs.getLong("stale_rows")));
    }

    private static MapSqlParameterSource values(TranslationInput input, String status, boolean manualOverride,
                                                 LocalDateTime translatedAt, LocalDateTime reviewedAt,
                                                 LocalDateTime now) {
        return new MapSqlParameterSource()
                .addValue("toiletId", input.toiletId()).addValue("locale", input.locale())
                .addValue("name", input.name()).addValue("road", input.roadAddress())
                .addValue("jibun", input.jibunAddress()).addValue("sourceHash", input.expectedSourceHash())
                .addValue("status", status).addValue("source", input.source())
                .addValue("addressStatus", addressStatus(input))
                .addValue("addressSource", addressSource(input))
                .addValue("manualOverride", manualOverride).addValue("translatedAt", translatedAt)
                .addValue("reviewedAt", reviewedAt).addValue("now", now);
    }

    private static Text map(ResultSet rs) throws SQLException {
        return new Text(rs.getLong("toilet_id"), rs.getString("locale"), rs.getString("name"),
                rs.getString("road_address"), rs.getString("jibun_address"), rs.getString("source_hash"),
                rs.getString("translation_status"), rs.getString("translation_source"),
                rs.getString("address_translation_status"), rs.getString("address_translation_source"),
                rs.getBoolean("manual_override"), rs.getLong("version"),
                rs.getObject("translated_at", LocalDateTime.class),
                rs.getObject("reviewed_at", LocalDateTime.class), rs.getBoolean("is_current"));
    }

    private static String addressStatus(TranslationInput input) {
        if ("ko".equals(input.locale())) return "SOURCE";
        return hasText(input.roadAddress()) || hasText(input.jibunAddress()) ? "TRANSLATED" : "NO_RESULT";
    }

    private static String addressSource(TranslationInput input) {
        return "ko".equals(input.locale()) ? "SOURCE" : input.source();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
