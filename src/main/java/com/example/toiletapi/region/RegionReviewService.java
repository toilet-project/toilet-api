package com.example.toiletapi.region;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.global.exception.ToiletNotFoundException;
import com.example.toiletapi.quality.dto.CorrectToiletCoordinateRequest;
import com.example.toiletapi.quality.dto.DuplicateCoordinateToiletResponse;
import com.example.toiletapi.quality.service.CoordinateQualityService;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.example.toiletapi.region.RegionReviewModels.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegionReviewService {
    // A confirmation is current only while the exact reviewed source remains unchanged.
    static final String EFFECTIVE_STATUS = """
            CASE WHEN d.toilet_id IS NOT NULL THEN 'VERIFIED'
                 WHEN t.latitude IS NULL OR t.longitude IS NULL THEN 'NO_COORDINATE'
                 WHEN a.toilet_id IS NULL THEN 'UNASSESSED'
                 WHEN a.source_revision <> t.region_revision THEN 'STALE'
                 WHEN a.status = 'VERIFIED' AND NOT (t.latitude <=> a.evaluated_latitude
                       AND t.longitude <=> a.evaluated_longitude) THEN 'STALE'
                 ELSE a.status END
            """;
    private static final String JOIN = """
             FROM toilet t
             LEFT JOIN toilet_region_assignment a ON a.toilet_id=t.toilet_id
             LEFT JOIN toilet_region_decision d ON d.toilet_id=t.toilet_id
               AND d.source_revision=t.region_revision
             LEFT JOIN region_sigungu_reference ar ON ar.sigungu_code=a.sigungu_code
             LEFT JOIN region_sigungu_reference dr ON dr.sigungu_code=d.sigungu_code
             LEFT JOIN toilet_region_assessment_history ah ON ah.assessment_id=a.assessment_id
            """;
    private static final String COLUMNS = """
            t.toilet_id, t.name, t.mng_no, t.latitude, t.longitude, t.road_address, t.jibun_address,
            a.status AS assessment_status, COALESCE(d.note,a.reason) AS reason,
            COALESCE(dr.sido_name,ar.sido_name) AS sido_name, COALESCE(dr.sido_code,ar.sido_code) AS sido_code,
            COALESCE(dr.sigungu_name,ar.sigungu_name) AS sigungu_name, COALESCE(d.sigungu_code,a.sigungu_code) AS sigungu_code,
            COALESCE(dr.city_name,ar.city_name) AS city_name, COALESCE(dr.district_name,ar.district_name) AS district_name,
            COALESCE(d.confirmed_at,a.checked_at) AS checked_at,
            """ + EFFECTIVE_STATUS + " AS effective_status ";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final NamedParameterJdbcTemplate jdbc;
    private final ToiletRepository toilets;
    private final CoordinateQualityService corrections;
    private final AuditLogService audit;

    public Page<Item> search(Filter filter, String keyword, int page, int size) {
        validatePage(page, size);
        String term = keyword == null ? "" : keyword.trim();
        if (term.length() > 100) throw new IllegalArgumentException("검색어는 100자 이하로 입력해 주세요.");
        String where = " WHERE 1=1 ";
        var params = new MapSqlParameterSource().addValue("limit", size).addValue("offset", (long) page * size);
        if (filter == Filter.REVIEW) where += " AND (" + EFFECTIVE_STATUS + ") <> 'VERIFIED' ";
        else if (filter != Filter.ALL) {
            where += " AND (" + EFFECTIVE_STATUS + ") = :status ";
            params.addValue("status", filter.name());
        }
        if (!term.isEmpty()) {
            params.addValue("keyword", "%" + escapeLike(term) + "%");
            where += " AND (t.name LIKE :keyword ESCAPE '!' OR t.road_address LIKE :keyword ESCAPE '!'"
                    + " OR t.jibun_address LIKE :keyword ESCAPE '!' OR t.mng_no LIKE :keyword ESCAPE '!') ";
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + JOIN + where, params, Long.class);
        var items = jdbc.query("SELECT " + COLUMNS + JOIN + where
                + " ORDER BY COALESCE(d.confirmed_at,a.checked_at) ASC, t.toilet_id ASC LIMIT :limit OFFSET :offset",
                params, (rs, n) -> item(rs));
        return page(items, page, size, total);
    }

    public Detail detail(long id) {
        var rows = jdbc.query("SELECT " + COLUMNS + """
                , CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(ah.result_json,'$.source.latitude')),'null') AS DECIMAL(10,7)) AS source_latitude,
                  CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(ah.result_json,'$.source.longitude')),'null') AS DECIMAL(10,7)) AS source_longitude,
                  NULLIF(JSON_UNQUOTE(JSON_EXTRACT(ah.result_json,'$.source.roadAddress')),'null') AS source_road_address,
                  NULLIF(JSON_UNQUOTE(JSON_EXTRACT(ah.result_json,'$.source.jibunAddress')),'null') AS source_jibun_address,
                  a.evaluated_latitude, a.evaluated_longitude, ah.result_json, t.data_source,
                  dr.sido_name AS confirmed_sido_name, dr.sido_code AS confirmed_sido_code,
                  dr.sigungu_name AS confirmed_sigungu_name, d.sigungu_code AS confirmed_sigungu_code,
                  dr.city_name AS confirmed_city_name, dr.district_name AS confirmed_district_name,
                  d.note AS confirmed_note, d.confirmed_at
                """ + JOIN + " WHERE t.toilet_id=:id", new MapSqlParameterSource("id", id), (rs, n) -> new Detail(
                item(rs), new Location(rs.getBigDecimal("source_latitude"), rs.getBigDecimal("source_longitude"),
                rs.getString("source_road_address"), rs.getString("source_jibun_address")),
                rs.getBigDecimal("evaluated_latitude"), rs.getBigDecimal("evaluated_longitude"), rs.getString("result_json"),
                rs.getString("data_source"), confirmation(rs)));
        if (rows.isEmpty()) throw new ToiletNotFoundException(id);
        return rows.getFirst();
    }

    public List<RegionOption> options(String keyword, int limit) {
        String term = keyword == null ? "" : keyword.trim().replaceAll("\\s+", " ");
        if (term.length() > 50) throw new IllegalArgumentException("지역 검색어는 50자 이하로 입력해 주세요.");
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("지역 검색 결과는 1~50개까지 요청할 수 있습니다.");
        var params = new MapSqlParameterSource("limit", limit);
        String where = " WHERE is_active=1 ";
        if (!term.isEmpty()) {
            params.addValue("keyword", "%" + escapeLike(term) + "%");
            params.addValue("compactKeyword", "%" + escapeLike(term.replace(" ", "")) + "%");
            where += " AND (display_name LIKE :keyword ESCAPE '!'"
                    + " OR REPLACE(display_name,' ','') LIKE :compactKeyword ESCAPE '!'"
                    + " OR sigungu_code LIKE :keyword ESCAPE '!') ";
        }
        return jdbc.query("""
                SELECT sido_name,sido_code,sigungu_name,sigungu_code,city_name,district_name
                FROM region_sigungu_reference
                """ + where + " ORDER BY sido_code,sigungu_code LIMIT :limit", params,
                (rs, n) -> new RegionOption(region(rs, "")));
    }

    public Page<History> history(long id, int page, int size) {
        validatePage(page, size);
        var params = new MapSqlParameterSource("id", id).addValue("limit", size).addValue("offset", (long) page * size);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM toilet_region_assessment_history WHERE toilet_id=:id", params, Long.class);
        var items = jdbc.query("""
                SELECT assessment_id,status,reason,algorithm_version,checked_at,result_json
                FROM toilet_region_assessment_history WHERE toilet_id=:id
                ORDER BY checked_at DESC,assessment_id DESC LIMIT :limit OFFSET :offset
                """, params, (rs, n) -> new History(rs.getLong("assessment_id"), rs.getString("status"),
                rs.getString("reason"), rs.getString("algorithm_version"), time(rs, "checked_at"), rs.getString("result_json")));
        return page(items, page, size, total);
    }

    @Transactional
    public Confirmation confirmDistrict(long adminId, long id, RegionConfirmation request) {
        Toilet toilet = toilets.findByIdForUpdate(id).orElseThrow(() -> new ToiletNotFoundException(id));
        requireUnchanged(toilet, request.expectedLocation());
        var matches = jdbc.query("""
                SELECT sido_name,sido_code,sigungu_name,sigungu_code,city_name,district_name
                FROM region_sigungu_reference WHERE sigungu_code=:code AND is_active=1
                """, new MapSqlParameterSource("code", request.sigunguCode()), (rs, n) -> region(rs, ""));
        if (matches.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "선택한 시·군·구를 확인할 수 없습니다.");
        RegionValue choice = matches.getFirst();
        OffsetDateTime confirmedAt = OffsetDateTime.now(SEOUL);
        var values = new MapSqlParameterSource()
                .addValue("id", id).addValue("sidoName", choice.sidoName()).addValue("sidoCode", choice.sidoCode())
                .addValue("sigunguName", choice.sigunguName()).addValue("sigunguCode", choice.sigunguCode())
                .addValue("cityName", choice.cityName()).addValue("districtName", choice.districtName())
                .addValue("note", request.note().trim()).addValue("latitude", toilet.getLatitude())
                .addValue("longitude", toilet.getLongitude()).addValue("roadAddress", toilet.getRoadAddress())
                .addValue("jibunAddress", toilet.getJibunAddress()).addValue("adminId", adminId)
                .addValue("confirmedAt", confirmedAt.toLocalDateTime());
        jdbc.update("""
                INSERT INTO toilet_region_override
                (toilet_id,sido_name,sido_code,sigungu_name,sigungu_code,city_name,district_name,note,
                 source_latitude,source_longitude,source_road_address,source_jibun_address,confirmed_by_user_id,confirmed_at)
                VALUES (:id,:sidoName,:sidoCode,:sigunguName,:sigunguCode,:cityName,:districtName,:note,
                        :latitude,:longitude,:roadAddress,:jibunAddress,:adminId,:confirmedAt)
                ON DUPLICATE KEY UPDATE sido_name=VALUES(sido_name),sido_code=VALUES(sido_code),
                  sigungu_name=VALUES(sigungu_name),sigungu_code=VALUES(sigungu_code),city_name=VALUES(city_name),
                  district_name=VALUES(district_name),note=VALUES(note),source_latitude=VALUES(source_latitude),
                  source_longitude=VALUES(source_longitude),source_road_address=VALUES(source_road_address),
                  source_jibun_address=VALUES(source_jibun_address),confirmed_by_user_id=VALUES(confirmed_by_user_id),
                  confirmed_at=VALUES(confirmed_at)
                """, values);
        audit.record(adminId, AuditAction.TOILET_REGION_CONFIRMED, "TOILET", id,
                Map.of("sigunguCode", choice.sigunguCode(), "regionName", displayName(choice)));
        return new Confirmation(choice, request.note().trim(), confirmedAt);
    }

    @Transactional
    public DuplicateCoordinateToiletResponse correct(long adminId, long id, Correction request) {
        Toilet toilet = toilets.findByIdForUpdate(id).orElseThrow(() -> new ToiletNotFoundException(id));
        requireUnchanged(toilet, request.expectedLocation());
        return corrections.correctToilet(adminId, id, new CorrectToiletCoordinateRequest(
                request.latitude(), request.longitude(), null, request.note()));
    }

    private void requireUnchanged(Toilet toilet, Location expected) {
        if (expected == null || !same(toilet.getLatitude(), expected.latitude())
                || !same(toilet.getLongitude(), expected.longitude())
                || !Objects.equals(toilet.getRoadAddress(), expected.roadAddress())
                || !Objects.equals(toilet.getJibunAddress(), expected.jibunAddress()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 작업에서 위치나 주소가 변경되었습니다. 새로고침 후 다시 확인해 주세요.");
    }

    static boolean same(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }
    private static String escapeLike(String term) {
        return term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
    private static String displayName(RegionValue region) {
        return region.sigunguName() == null ? region.sidoName() : region.sidoName() + " " + region.sigunguName();
    }
    private static void validatePage(int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100)
            throw new IllegalArgumentException("페이지는 0 이상, 크기는 1~100이어야 합니다.");
    }
    private static <T> Page<T> page(List<T> items, int page, int size, Long total) {
        long count = total == null ? 0 : total;
        return new Page<>(items, page, size, count, (int) Math.ceil((double) count / size));
    }
    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime().atZone(SEOUL).toOffsetDateTime();
    }
    private static Item item(ResultSet rs) throws SQLException {
        return new Item(rs.getLong("toilet_id"), rs.getString("name"), rs.getString("mng_no"),
                new Location(rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("road_address"), rs.getString("jibun_address")),
                rs.getString("effective_status"), rs.getString("assessment_status"), rs.getString("reason"),
                rs.getString("sido_name"), rs.getString("sido_code"), rs.getString("sigungu_name"), rs.getString("sigungu_code"),
                rs.getString("city_name"), rs.getString("district_name"), time(rs, "checked_at"));
    }
    private static RegionValue region(ResultSet rs, String prefix) throws SQLException {
        return new RegionValue(rs.getString(prefix + "sido_name"), rs.getString(prefix + "sido_code"),
                rs.getString(prefix + "sigungu_name"), rs.getString(prefix + "sigungu_code"),
                rs.getString(prefix + "city_name"), rs.getString(prefix + "district_name"));
    }
    private static Confirmation confirmation(ResultSet rs) throws SQLException {
        if (rs.getString("confirmed_sigungu_code") == null) return null;
        RegionValue value = new RegionValue(rs.getString("confirmed_sido_name"), rs.getString("confirmed_sido_code"),
                rs.getString("confirmed_sigungu_name"), rs.getString("confirmed_sigungu_code"),
                rs.getString("confirmed_city_name"), rs.getString("confirmed_district_name"));
        return new Confirmation(value, rs.getString("confirmed_note"), time(rs, "confirmed_at"));
    }
}
