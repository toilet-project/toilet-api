package com.example.toiletapi.region;

import com.example.toiletapi.global.exception.ToiletNotFoundException;
import com.example.toiletapi.quality.dto.CorrectToiletCoordinateRequest;
import com.example.toiletapi.quality.dto.DuplicateCoordinateToiletResponse;
import com.example.toiletapi.quality.service.CoordinateQualityService;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
    // Do not expose an assessment based on old coordinates/addresses as a current VERIFIED result.
    static final String EFFECTIVE_STATUS = """
            CASE WHEN t.latitude IS NULL OR t.longitude IS NULL THEN 'NO_COORDINATE'
                 WHEN r.toilet_id IS NULL THEN 'UNASSESSED'
                 WHEN NOT (t.latitude <=> r.source_latitude AND t.longitude <=> r.source_longitude
                       AND BINARY t.road_address <=> BINARY r.source_road_address
                       AND BINARY t.jibun_address <=> BINARY r.source_jibun_address) THEN 'STALE'
                 WHEN r.status = 'VERIFIED' AND NOT (t.latitude <=> r.evaluated_latitude
                       AND t.longitude <=> r.evaluated_longitude) THEN 'STALE'
                 ELSE r.status END
            """;
    private static final String JOIN = " FROM toilet t LEFT JOIN toilet_region r ON r.toilet_id=t.toilet_id ";
    private static final String COLUMNS = """
            t.toilet_id, t.name, t.mng_no, t.latitude, t.longitude, t.road_address, t.jibun_address,
            r.status AS assessment_status, r.reason, r.sido_name, r.sido_code, r.sigungu_name,
            r.sigungu_code, r.city_name, r.district_name, r.checked_at,
            """ + EFFECTIVE_STATUS + " AS effective_status ";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final NamedParameterJdbcTemplate jdbc;
    private final ToiletRepository toilets;
    private final CoordinateQualityService corrections;

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
            // Escape SQL wildcard characters; all user input remains bound parameters.
            params.addValue("keyword", "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            where += " AND (t.name LIKE :keyword ESCAPE '!' OR t.road_address LIKE :keyword ESCAPE '!'"
                    + " OR t.jibun_address LIKE :keyword ESCAPE '!' OR t.mng_no LIKE :keyword ESCAPE '!') ";
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + JOIN + where, params, Long.class);
        var items = jdbc.query("SELECT " + COLUMNS + JOIN + where
                + " ORDER BY r.checked_at ASC, t.toilet_id ASC LIMIT :limit OFFSET :offset", params, (rs, n) -> item(rs));
        return page(items, page, size, total);
    }

    public Detail detail(long id) {
        var rows = jdbc.query("SELECT " + COLUMNS + """
                , r.source_latitude, r.source_longitude, r.source_road_address, r.source_jibun_address,
                  r.evaluated_latitude, r.evaluated_longitude, r.result_json
                """ + JOIN + " WHERE t.toilet_id=:id", new MapSqlParameterSource("id", id), (rs, n) -> new Detail(
                item(rs), new Location(rs.getBigDecimal("source_latitude"), rs.getBigDecimal("source_longitude"),
                rs.getString("source_road_address"), rs.getString("source_jibun_address")),
                rs.getBigDecimal("evaluated_latitude"), rs.getBigDecimal("evaluated_longitude"), rs.getString("result_json")));
        if (rows.isEmpty()) throw new ToiletNotFoundException(id);
        return rows.getFirst();
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
                rs.getString("reason"), rs.getString("algorithm_version"), time(rs), rs.getString("result_json")));
        return page(items, page, size, total);
    }

    @Transactional
    public DuplicateCoordinateToiletResponse correct(long adminId, long id, Correction request) {
        var toilet = toilets.findByIdForUpdate(id).orElseThrow(() -> new ToiletNotFoundException(id));
        var expected = request.expectedLocation();
        if (expected == null || !same(toilet.getLatitude(), expected.latitude())
                || !same(toilet.getLongitude(), expected.longitude())
                || !Objects.equals(toilet.getRoadAddress(), expected.roadAddress())
                || !Objects.equals(toilet.getJibunAddress(), expected.jibunAddress())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 작업에서 위치나 주소가 변경되었습니다. 새로고침 후 다시 확인해 주세요.");
        }
        // Existing path performs server reverse geocoding, revision/audit logging and ADMIN_CONFIRMED protection.
        // No region status is manually overridden; the normalizer re-evaluates the new source asynchronously.
        return corrections.correctToilet(adminId, id, new CorrectToiletCoordinateRequest(
                request.latitude(), request.longitude(), null, request.note()));
    }

    static boolean same(BigDecimal left, BigDecimal right) { return left == null ? right == null : right != null && left.compareTo(right) == 0; }
    private static void validatePage(int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) throw new IllegalArgumentException("페이지는 0 이상, 크기는 1~100이어야 합니다.");
    }
    private static <T> Page<T> page(java.util.List<T> items, int page, int size, Long total) {
        long count = total == null ? 0 : total;
        return new Page<>(items, page, size, count, (int) Math.ceil((double) count / size));
    }
    private static OffsetDateTime time(ResultSet rs) throws SQLException {
        var value = rs.getTimestamp("checked_at");
        return value == null ? null : value.toLocalDateTime().atZone(SEOUL).toOffsetDateTime();
    }
    private static Item item(ResultSet rs) throws SQLException {
        return new Item(rs.getLong("toilet_id"), rs.getString("name"), rs.getString("mng_no"),
                new Location(rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("road_address"), rs.getString("jibun_address")),
                rs.getString("effective_status"), rs.getString("assessment_status"), rs.getString("reason"),
                rs.getString("sido_name"), rs.getString("sido_code"), rs.getString("sigungu_name"), rs.getString("sigungu_code"),
                rs.getString("city_name"), rs.getString("district_name"), time(rs));
    }
}
