package com.example.toiletapi.toilet.admin;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.global.exception.ToiletNotFoundException;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.model.ToiletEditableData;
import com.example.toiletapi.toilet.repository.ToiletRegionProjection;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
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

import static com.example.toiletapi.toilet.admin.AdminToiletModels.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminToiletService {
    private static final String LIST_FROM = """
            FROM toilet t
            LEFT JOIN current_toilet_region r ON r.toilet_id=t.toilet_id
            """;
    private static final String LIST_COLUMNS = """
            t.toilet_id,t.name,t.mng_no,t.toilet_type,t.road_address,t.jibun_address,
            t.latitude,t.longitude,t.visibility_status,
            r.sido_name,r.sido_code,r.sigungu_name,r.sigungu_code,r.city_name,r.district_name
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ToiletRepository toilets;
    private final AuditLogService audit;

    public Page<Item> search(String keyword, String sidoCode, String sigunguCode, int page, int size) {
        validatePage(page, size);
        String term = normalizedKeyword(keyword);
        String normalizedSido = code(sidoCode, 2, "시·도");
        String normalizedSigungu = code(sigunguCode, 5, "시·군·구");
        if (!normalizedSigungu.isEmpty() && normalizedSido.isEmpty()) {
            throw new IllegalArgumentException("시·군·구를 선택하려면 시·도를 먼저 선택해 주세요.");
        }

        var parameters = new MapSqlParameterSource()
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        if (!term.isEmpty()) {
            parameters.addValue("keyword", "%" + escapeLike(term) + "%")
                    .addValue("prefix", escapeLike(term) + "%")
                    .addValue("exact", term);
            where.append(" AND COALESCE(t.name,'') LIKE :keyword ESCAPE '!' ");
        }
        if (!normalizedSido.isEmpty()) {
            parameters.addValue("sidoCode", normalizedSido);
            where.append(" AND r.sido_code=:sidoCode ");
        }
        if (!normalizedSigungu.isEmpty()) {
            parameters.addValue("sigunguCode", normalizedSigungu);
            where.append(" AND r.sigungu_code=:sigunguCode ");
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) " + LIST_FROM + where, parameters, Long.class);
        String order = term.isEmpty()
                ? " ORDER BY t.toilet_id DESC "
                : " ORDER BY CASE WHEN t.name=:exact THEN 0 WHEN t.name LIKE :prefix ESCAPE '!' THEN 1 ELSE 2 END,"
                    + " CHAR_LENGTH(t.name),t.name,t.toilet_id ";
        List<Item> items = jdbc.query("SELECT " + LIST_COLUMNS + LIST_FROM + where + order
                        + " LIMIT :limit OFFSET :offset", parameters, this::item);
        long count = total == null ? 0 : total;
        return new Page<>(items, page, size, count, count == 0 ? 0 : (int) Math.ceil((double) count / size));
    }

    public List<Suggestion> suggestions(String keyword, int limit) {
        String term = normalizedKeyword(keyword);
        if (term.isEmpty()) return List.of();
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("연관검색어는 1~20개까지 요청할 수 있습니다.");
        var parameters = new MapSqlParameterSource()
                .addValue("keyword", "%" + escapeLike(term) + "%")
                .addValue("prefix", escapeLike(term) + "%")
                .addValue("exact", term)
                .addValue("limit", limit);
        return jdbc.query("""
                SELECT MIN(t.toilet_id) AS toilet_id,t.name,
                       MIN(COALESCE(NULLIF(t.road_address,''),NULLIF(t.jibun_address,''),'주소 정보 없음')) AS address
                  FROM toilet t
                 WHERE COALESCE(t.name,'') LIKE :keyword ESCAPE '!'
                 GROUP BY t.name
                 ORDER BY CASE WHEN t.name=:exact THEN 0 WHEN t.name LIKE :prefix ESCAPE '!' THEN 1 ELSE 2 END,
                          CHAR_LENGTH(t.name),t.name
                 LIMIT :limit
                """, parameters, (rs, rowNumber) -> new Suggestion(
                rs.getLong("toilet_id"), rs.getString("name"), rs.getString("address")));
    }

    public List<RegionOption> regions() {
        return jdbc.query("""
                SELECT sido_name,sido_code,sigungu_name,sigungu_code
                  FROM region_sigungu_reference
                 WHERE is_active=1
                 ORDER BY sido_code,sigungu_code
                """, new MapSqlParameterSource(), (rs, rowNumber) -> new RegionOption(
                rs.getString("sido_name"), rs.getString("sido_code"),
                rs.getString("sigungu_name"), rs.getString("sigungu_code")));
    }

    public Detail detail(long id) {
        Toilet toilet = toilets.findById(id).orElseThrow(() -> new ToiletNotFoundException(id));
        return detail(toilet);
    }

    @Transactional
    public Detail update(long adminId, long id, UpdateRequest request) {
        Toilet toilet = toilets.findByIdForUpdate(id).orElseThrow(() -> new ToiletNotFoundException(id));
        ToiletEditableData before = editableData(toilet);
        String currentToken = snapshotToken(before);
        if (!MessageDigest.isEqual(currentToken.getBytes(StandardCharsets.US_ASCII),
                request.snapshotToken().getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "다른 작업에서 화장실 정보가 변경되었습니다. 최신 값을 다시 불러와 주세요.");
        }

        ToiletEditableData after = editableData(request.editable());
        List<String> changedFields = changedFields(before, after);
        if (!changedFields.isEmpty()) {
            toilet.applyAdminUpdate(after);
            toilets.flush();
            audit.record(adminId, AuditAction.TOILET_ADMIN_UPDATED, "TOILET", id,
                    Map.of("changedFields", changedFields, "changedFieldCount", changedFields.size()));
        }
        return detail(toilet);
    }

    private Detail detail(Toilet toilet) {
        Region region = toilets.findCurrentRegion(toilet.getId()).map(this::region).orElse(null);
        ToiletEditableData data = editableData(toilet);
        return new Detail(toilet.getId(), toilet.getManagementNumber(), toilet.getVisibilityStatus(),
                toilet.getCoordinateSource(), Objects.requireNonNullElse(toilet.getRegionRevision(), 1L),
                toilet.getDataBaseDate(), toilet.getDataSource(), region, snapshotToken(data), editable(data));
    }

    private Item item(ResultSet rs, int rowNumber) throws SQLException {
        return new Item(rs.getLong("toilet_id"), rs.getString("name"), rs.getString("mng_no"),
                rs.getString("toilet_type"), rs.getString("road_address"), rs.getString("jibun_address"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("visibility_status"),
                region(rs));
    }

    private Region region(ResultSet rs) throws SQLException {
        if (rs.getString("sido_code") == null && rs.getString("sigungu_code") == null) return null;
        return new Region(rs.getString("sido_name"), rs.getString("sido_code"),
                rs.getString("sigungu_name"), rs.getString("sigungu_code"),
                rs.getString("city_name"), rs.getString("district_name"));
    }

    private Region region(ToiletRegionProjection value) {
        return new Region(value.getSidoName(), value.getSidoCode(), value.getSigunguName(), value.getSigunguCode(),
                value.getCityName(), value.getDistrictName());
    }

    private static ToiletEditableData editableData(Toilet toilet) {
        return new ToiletEditableData(toilet.getName(), toilet.getToiletType(), toilet.getRoadAddress(),
                toilet.getJibunAddress(), toilet.getLatitude(), toilet.getLongitude(),
                toilet.getMaleToiletCount(), toilet.getMaleUrinalCount(), toilet.getMaleDisabledToiletCount(),
                toilet.getMaleDisabledUrinalCount(), toilet.getMaleChildToiletCount(), toilet.getMaleChildUrinalCount(),
                toilet.getFemaleToiletCount(), toilet.getFemaleDisabledToiletCount(), toilet.getFemaleChildToiletCount(),
                toilet.getAgencyName(), toilet.getPhoneNumber(), toilet.getOpenTime(), toilet.getOpenTimeDetail(),
                toilet.getInstallationDate(), toilet.getHasEmergencyBell(), toilet.getEmergencyBellLocation(),
                toilet.getHasCctv(), toilet.getHasDiaperTable(), toilet.getDiaperTableLocation());
    }

    private static ToiletEditableData editableData(Editable value) {
        return new ToiletEditableData(cleanRequired(value.name()), clean(value.toiletType()), clean(value.roadAddress()),
                clean(value.jibunAddress()), value.latitude(), value.longitude(), value.maleToiletCount(),
                value.maleUrinalCount(), value.maleDisabledToiletCount(), value.maleDisabledUrinalCount(),
                value.maleChildToiletCount(), value.maleChildUrinalCount(), value.femaleToiletCount(),
                value.femaleDisabledToiletCount(), value.femaleChildToiletCount(), clean(value.agencyName()),
                clean(value.phoneNumber()), clean(value.openTime()), clean(value.openTimeDetail()),
                clean(value.installationDate()), clean(value.hasEmergencyBell()), clean(value.emergencyBellLocation()),
                clean(value.hasCctv()), clean(value.hasDiaperTable()), clean(value.diaperTableLocation()));
    }

    private static Editable editable(ToiletEditableData value) {
        return new Editable(value.name(), value.toiletType(), value.roadAddress(), value.jibunAddress(),
                value.latitude(), value.longitude(), value.maleToiletCount(), value.maleUrinalCount(),
                value.maleDisabledToiletCount(), value.maleDisabledUrinalCount(), value.maleChildToiletCount(),
                value.maleChildUrinalCount(), value.femaleToiletCount(), value.femaleDisabledToiletCount(),
                value.femaleChildToiletCount(), value.agencyName(), value.phoneNumber(), value.openTime(),
                value.openTimeDetail(), value.installationDate(), value.hasEmergencyBell(),
                value.emergencyBellLocation(), value.hasCctv(), value.hasDiaperTable(), value.diaperTableLocation());
    }

    private static List<String> changedFields(ToiletEditableData before, ToiletEditableData after) {
        String[] names = {"name", "toiletType", "roadAddress", "jibunAddress", "latitude", "longitude",
                "maleToiletCount", "maleUrinalCount", "maleDisabledToiletCount", "maleDisabledUrinalCount",
                "maleChildToiletCount", "maleChildUrinalCount", "femaleToiletCount", "femaleDisabledToiletCount",
                "femaleChildToiletCount", "agencyName", "phoneNumber", "openTime", "openTimeDetail",
                "installationDate", "hasEmergencyBell", "emergencyBellLocation", "hasCctv", "hasDiaperTable",
                "diaperTableLocation"};
        Object[] left = values(before);
        Object[] right = values(after);
        List<String> changed = new ArrayList<>();
        for (int index = 0; index < names.length; index++) {
            if (!same(left[index], right[index])) changed.add(names[index]);
        }
        return List.copyOf(changed);
    }

    private static Object[] values(ToiletEditableData value) {
        return new Object[]{value.name(), value.toiletType(), value.roadAddress(), value.jibunAddress(),
                value.latitude(), value.longitude(), value.maleToiletCount(), value.maleUrinalCount(),
                value.maleDisabledToiletCount(), value.maleDisabledUrinalCount(), value.maleChildToiletCount(),
                value.maleChildUrinalCount(), value.femaleToiletCount(), value.femaleDisabledToiletCount(),
                value.femaleChildToiletCount(), value.agencyName(), value.phoneNumber(), value.openTime(),
                value.openTimeDetail(), value.installationDate(), value.hasEmergencyBell(), value.emergencyBellLocation(),
                value.hasCctv(), value.hasDiaperTable(), value.diaperTableLocation()};
    }

    private static boolean same(Object left, Object right) {
        if (left instanceof BigDecimal leftNumber && right instanceof BigDecimal rightNumber) {
            return leftNumber.compareTo(rightNumber) == 0;
        }
        return Objects.equals(left, right);
    }

    static String snapshotToken(ToiletEditableData value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object item : values(value)) {
                String normalized = item instanceof BigDecimal number ? number.stripTrailingZeros().toPlainString()
                        : Objects.toString(item, "<null>");
                digest.update(normalized.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("화장실 수정 토큰을 만들 수 없습니다.", exception);
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0) throw new IllegalArgumentException("페이지는 0 이상이어야 합니다.");
        if (size < 1 || size > 100) throw new IllegalArgumentException("페이지 크기는 1~100이어야 합니다.");
    }

    private static String normalizedKeyword(String value) {
        String result = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (result.length() > 100) throw new IllegalArgumentException("검색어는 100자 이하로 입력해 주세요.");
        return result;
    }

    private static String code(String value, int length, String label) {
        String result = value == null ? "" : value.trim();
        if (!result.isEmpty() && !result.matches("\\d{" + length + "}")) {
            throw new IllegalArgumentException(label + " 코드를 확인해 주세요.");
        }
        return result;
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static String cleanRequired(String value) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) throw new IllegalArgumentException("화장실명을 입력해 주세요.");
        return result;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
