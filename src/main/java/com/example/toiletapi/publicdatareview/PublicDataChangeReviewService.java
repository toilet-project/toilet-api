package com.example.toiletapi.publicdatareview;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.global.time.KoreanTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.example.toiletapi.publicdatareview.PublicDataChangeReviewModels.*;

@Service
@Transactional(readOnly = true)
public class PublicDataChangeReviewService {
    private static final ZoneOffset SEOUL_OFFSET = ZoneOffset.ofHours(9);
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditLogService audit;

    public PublicDataChangeReviewService(NamedParameterJdbcTemplate jdbc, AuditLogService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Page search(Status status, String keyword, Integer receivedWithinDays,
                       int page, int size, String sort) {
        validatePage(page, size);
        String term = keyword == null ? "" : keyword.trim();
        if (term.length() > 100) throw new IllegalArgumentException("검색어는 100자 이하로 입력해 주세요.");
        if (receivedWithinDays != null && (receivedWithinDays < 1 || receivedWithinDays > 365))
            throw new IllegalArgumentException("수신 기간은 1~365일이어야 합니다.");
        if (!"lastReceivedAt,desc".equalsIgnoreCase(sort))
            throw new IllegalArgumentException("지원하지 않는 정렬 방식입니다.");

        var params = new MapSqlParameterSource()
                .addValue("limit", size).addValue("offset", (long) page * size);
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        if (status != null) {
            where.append(" AND r.status=:status ");
            params.addValue("status", status.name());
        }
        if (!term.isEmpty()) {
            where.append(" AND (t.name LIKE :keyword ESCAPE '!' OR t.mng_no LIKE :keyword ESCAPE '!') ");
            params.addValue("keyword", "%" + escapeLike(term) + "%");
        }
        if (receivedWithinDays != null) {
            where.append(" AND r.last_received_at>=:receivedAfter ");
            params.addValue("receivedAfter", KoreanTime.now().minusDays(receivedWithinDays));
        }

        String from = " FROM public_data_change_review r JOIN toilet t ON t.toilet_id=r.toilet_id ";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, params, Long.class);
        List<ReviewItem> items = jdbc.query("""
                SELECT r.review_id,r.toilet_id,t.name,t.mng_no,r.status,r.changed_fields,
                       r.first_received_at,r.last_received_at,r.receipt_count,r.version,
                       CASE WHEN r.proposal_latitude IS NULL OR r.proposal_longitude IS NULL
                                  OR r.proposal_latitude NOT BETWEEN -90 AND 90
                                  OR r.proposal_longitude NOT BETWEEN -180 AND 180
                                  OR (NULLIF(TRIM(r.proposal_road_address),'') IS NULL
                                      AND NULLIF(TRIM(r.proposal_jibun_address),'') IS NULL)
                            THEN 1 ELSE 0 END AS has_warning
                """ + from + where + " ORDER BY r.last_received_at DESC,r.review_id DESC LIMIT :limit OFFSET :offset",
                params, (rs, row) -> reviewItem(rs));
        Summary summary = summary();
        long count = total == null ? 0 : total;
        return new Page(items, page, size, count, count == 0 ? 0 : (int) Math.ceil((double) count / size), summary);
    }

    public Detail detail(long id) {
        return loadDetail(id, false);
    }

    @Transactional
    public Detail decide(long adminId, long id, DecisionRequest request) {
        String note = request.note() == null ? "" : request.note().trim();
        Candidate candidate = lock(id);
        if (candidate.baselineHash().equals(request.expectedBaselineHash())
                && recordedDecision(adminId, candidate.id(), request.action(), note, request.expectedVersion()))
            return loadDetail(id, false);
        if (candidate.status() != Status.PENDING)
            throw conflict("이미 처리가 끝난 변경 후보입니다.");
        if (candidate.version() != request.expectedVersion()
                || !candidate.baselineHash().equals(request.expectedBaselineHash()))
            throw conflict("변경 후보가 갱신되었습니다. 새로고침 후 다시 확인해 주세요.");
        HiddenContext hidden = hiddenContext(id);
        if (hidden != null && !hiddenStillCurrent(id)) throw conflict("숨김 상태가 변경되었습니다. 새 후보를 확인해 주세요.");
        String currentHash = comparisonHash(hidden, candidate.currentLatitude(), candidate.currentLongitude(),
                candidate.currentRoadAddress(), candidate.currentJibunAddress());
        if (!currentHash.equals(candidate.baselineHash())) {
            throw conflict("현재 확정값이 변경되었습니다. 새 후보를 확인해 주세요.");
        }

        if (request.action() != Action.DEFER && note.isEmpty())
            throw new IllegalArgumentException("변경 반영 또는 현재 값 유지의 근거를 입력해 주세요.");

        long decisionVersion = candidate.version();
        if (request.action() == Action.APPLY) apply(adminId, candidate, note);
        else if (request.action() == Action.KEEP_CURRENT) close(candidate.id(), Status.KEPT_CURRENT, note);
        else jdbc.update("UPDATE public_data_change_review SET version=version+1,updated_at=:now WHERE review_id=:id",
                values(candidate.id()).addValue("now", KoreanTime.now()));

        jdbc.update("""
                INSERT INTO public_data_change_decision
                    (review_id,action,note,decided_by_user_id,decided_at,candidate_version)
                VALUES (:id,:action,:note,:adminId,:now,:version)
                """, values(candidate.id()).addValue("action", request.action().name())
                .addValue("note", emptyToNull(note)).addValue("adminId", adminId)
                .addValue("now", KoreanTime.now()).addValue("version", decisionVersion));

        AuditAction auditAction = switch (request.action()) {
            case APPLY -> AuditAction.PUBLIC_DATA_CHANGE_APPLIED;
            case KEEP_CURRENT -> AuditAction.PUBLIC_DATA_CHANGE_KEPT;
            case DEFER -> AuditAction.PUBLIC_DATA_CHANGE_DEFERRED;
        };
        audit.record(adminId, auditAction, "PUBLIC_DATA_CHANGE_REVIEW", candidate.id(),
                Map.of("toiletId", candidate.toiletId(), "action", request.action().name()));
        return loadDetail(id, false);
    }

    private void apply(long adminId, Candidate candidate, String note) {
        HiddenContext hidden = hiddenContext(candidate.id());
        List<ValidationIssue> issues = validation(candidate.proposalLatitude(), candidate.proposalLongitude(),
                candidate.proposalRoadAddress(), candidate.proposalJibunAddress());
        if (issues.stream().anyMatch(ValidationIssue::blocking))
            throw new IllegalArgumentException("제안 좌표와 주소를 보완한 뒤 반영해 주세요.");
        LocalDateTime now = KoreanTime.now();
        jdbc.update("""
                INSERT INTO coordinate_revision
                    (toilet_id,report_id,previous_latitude,previous_longitude,applied_latitude,applied_longitude,
                     previous_road_address,previous_jibun_address,applied_road_address,applied_jibun_address,
                     applied_by_user_id,applied_at,source)
                VALUES (:toiletId,NULL,:previousLatitude,:previousLongitude,:latitude,:longitude,
                        :previousRoad,:previousJibun,:road,:jibun,:adminId,:now,'PUBLIC_DATA_REVIEW')
                """, values(candidate.id()).addValue("toiletId", candidate.toiletId())
                .addValue("previousLatitude", candidate.currentLatitude())
                .addValue("previousLongitude", candidate.currentLongitude())
                .addValue("latitude", candidate.proposalLatitude()).addValue("longitude", candidate.proposalLongitude())
                .addValue("previousRoad", candidate.currentRoadAddress()).addValue("previousJibun", candidate.currentJibunAddress())
                .addValue("road", candidate.proposalRoadAddress()).addValue("jibun", candidate.proposalJibunAddress())
                .addValue("adminId", adminId).addValue("now", now));
        int changed = jdbc.update("""
                UPDATE toilet SET latitude=:latitude,longitude=:longitude,road_address=:road,jibun_address=:jibun,
                       coordinate_source='ADMIN_CONFIRMED',region_revision=COALESCE(region_revision,1)+1
                 WHERE toilet_id=:toiletId
                """, values(candidate.id()).addValue("toiletId", candidate.toiletId())
                .addValue("latitude", candidate.proposalLatitude()).addValue("longitude", candidate.proposalLongitude())
                .addValue("road", candidate.proposalRoadAddress()).addValue("jibun", candidate.proposalJibunAddress()));
        if (changed != 1) throw new IllegalStateException("변경할 화장실을 찾지 못했습니다.");
        if (hidden != null) {
            if (hidden.proposalName() == null || hidden.proposalName().isBlank()) throw new IllegalArgumentException("제안 시설명을 확인해 주세요.");
            jdbc.update("UPDATE toilet SET name=:name WHERE toilet_id=:id", new MapSqlParameterSource("id",candidate.toiletId()).addValue("name",hidden.proposalName()));
            // Applying source values never releases the independently managed visibility decision.
        }
        close(candidate.id(), Status.APPLIED, note);
    }

    private void close(long id, Status status, String reason) {
        jdbc.update("""
                UPDATE public_data_change_review
                   SET status=:status,status_reason=:reason,active_toilet_id=NULL,version=version+1,updated_at=:now
                 WHERE review_id=:id AND status='PENDING'
                """, values(id).addValue("status", status.name()).addValue("reason", emptyToNull(reason))
                .addValue("now", KoreanTime.now()));
    }

    private Candidate lock(long id) {
        List<Long> toiletIds = jdbc.query("SELECT toilet_id FROM public_data_change_review WHERE review_id=:id",
                values(id), (rs, row) -> rs.getLong(1));
        if (toiletIds.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "변경 후보를 찾을 수 없습니다.");
        long toiletId = toiletIds.getFirst();
        List<CurrentValue> currentRows = jdbc.query("""
                SELECT latitude,longitude,road_address,jibun_address FROM toilet
                 WHERE toilet_id=:toiletId FOR UPDATE
                """, new MapSqlParameterSource("toiletId", toiletId), (rs, row) -> new CurrentValue(
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
                rs.getString("road_address"), rs.getString("jibun_address")));
        if (currentRows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "화장실을 찾을 수 없습니다.");
        CurrentValue current = currentRows.getFirst();
        List<Candidate> rows = jdbc.query("""
                SELECT review_id,toilet_id,status,version,baseline_hash,proposal_latitude,proposal_longitude,
                       proposal_road_address,proposal_jibun_address
                  FROM public_data_change_review WHERE review_id=:id FOR UPDATE
                """, values(id), (rs, row) -> new Candidate(rs.getLong("review_id"), rs.getLong("toilet_id"),
                Status.valueOf(rs.getString("status")), rs.getLong("version"), rs.getString("baseline_hash"),
                rs.getBigDecimal("proposal_latitude"), rs.getBigDecimal("proposal_longitude"),
                rs.getString("proposal_road_address"), rs.getString("proposal_jibun_address"),
                current.latitude(), current.longitude(), current.roadAddress(), current.jibunAddress()));
        if (rows.isEmpty() || rows.getFirst().toiletId() != toiletId)
            throw conflict("검토 대상이 변경되었습니다. 새로고침해 주세요.");
        return rows.getFirst();
    }

    private boolean recordedDecision(long adminId, long reviewId, Action action, String note, long candidateVersion) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM public_data_change_decision
                 WHERE review_id=:id AND action=:action AND decided_by_user_id=:adminId
                   AND candidate_version=:version
                   AND ((note IS NULL AND :note IS NULL) OR note=:note)
                """, values(reviewId).addValue("action", action.name()).addValue("adminId", adminId)
                .addValue("version", candidateVersion).addValue("note", emptyToNull(note)), Long.class);
        return count != null && count > 0;
    }

    private Detail loadDetail(long id, boolean forUpdate) {
        List<DetailRow> rows = jdbc.query("""
                SELECT r.review_id,r.toilet_id,t.name,t.mng_no,r.status,r.changed_fields,r.first_received_at,
                       r.last_received_at,r.receipt_count,r.version,r.baseline_hash,
                       r.proposal_latitude,r.proposal_longitude,r.proposal_road_address,r.proposal_jibun_address,
                       r.provider_updated_at,t.latitude,t.longitude,t.road_address,t.jibun_address,t.data_source,
                       CASE WHEN r.proposal_latitude IS NULL OR r.proposal_longitude IS NULL
                                  OR (NULLIF(TRIM(r.proposal_road_address),'') IS NULL
                                      AND NULLIF(TRIM(r.proposal_jibun_address),'') IS NULL)
                            THEN 1 ELSE 0 END AS has_warning
                  FROM public_data_change_review r JOIN toilet t ON t.toilet_id=r.toilet_id
                 WHERE r.review_id=:id
                """ + (forUpdate ? " FOR UPDATE" : ""), values(id), (rs, row) -> detailRow(rs));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "변경 후보를 찾을 수 없습니다.");
        DetailRow row = rows.getFirst();
        HiddenContext hidden = hiddenContext(id);
        String currentHash = comparisonHash(hidden, row.currentLatitude(), row.currentLongitude(), row.currentRoadAddress(), row.currentJibunAddress());
        Confirmation confirmation = latestConfirmation(row.toiletId());
        Receipt receipt = latestReceipt(row.id());
        var current = new ProtectedValue(row.currentLatitude(), row.currentLongitude(), row.currentRoadAddress(),
                row.currentJibunAddress(), confirmation.at(), confirmation.by());
        var proposal = new Proposal(row.proposalLatitude(), row.proposalLongitude(), row.proposalRoadAddress(),
                row.proposalJibunAddress(), time(row.providerUpdatedAt()));
        ReviewItem item = new ReviewItem(row.id(), row.toiletId(), row.name(), row.managementNumber(), row.status(),
                fields(row.changedFields()), time(row.firstReceivedAt()), time(row.lastReceivedAt()), row.receiptCount(),
                row.hasWarning(), row.version());
        return new Detail(item, Objects.toString(row.dataSource(), "PUBLIC_DATA"), row.baselineHash(),
                row.status() == Status.PENDING && (!currentHash.equals(row.baselineHash()) || (hidden != null && !hiddenStillCurrent(id))), current, proposal,
                distance(row.currentLatitude(), row.currentLongitude(), row.proposalLatitude(), row.proposalLongitude()),
                receipt, new Validation(validation(row.proposalLatitude(), row.proposalLongitude(),
                row.proposalRoadAddress(), row.proposalJibunAddress())), decisions(row.id()), hidden);
    }

    private HiddenContext hiddenContext(long id) {
        var rows=jdbc.query("""
            SELECT r.hidden_event_id,r.baseline_name,r.proposal_name,t.name,t.visibility_status,
                   e.representative_toilet_id,e.reason,e.occurred_at
            FROM public_data_change_review r JOIN toilet t ON t.toilet_id=r.toilet_id
            JOIN toilet_visibility_event e ON e.event_id=r.hidden_event_id WHERE r.review_id=:id
            """,values(id),(rs,n)->new HiddenContext(rs.getLong("hidden_event_id"),rs.getObject("representative_toilet_id",Long.class),rs.getString("reason"),time(rs.getTimestamp("occurred_at").toLocalDateTime()),rs.getString("visibility_status"),rs.getString("baseline_name"),rs.getString("name"),rs.getString("proposal_name")));
        return rows.isEmpty()?null:rows.getFirst();
    }
    private boolean hiddenStillCurrent(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM public_data_change_review r JOIN toilet t ON t.toilet_id=r.toilet_id WHERE r.review_id=:id AND t.visibility_status='HIDDEN_DUPLICATE' AND r.hidden_event_id=t.hidden_event_id",values(id),Long.class)==1;
    }
    private static String comparisonHash(HiddenContext hidden,BigDecimal lat,BigDecimal lng,String road,String jibun) {
        return hidden==null?hash(lat,lng,road,jibun):hashNamed(hidden.currentName(),lat,lng,road,jibun);
    }
    static String hashNamed(String name,BigDecimal lat,BigDecimal lng,String road,String jibun) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((normalize(name)+"\u001f"+hash(lat,lng,road,jibun)).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }

    private Confirmation latestConfirmation(long toiletId) {
        List<Confirmation> rows = jdbc.query("""
                SELECT r.applied_at,u.display_name
                  FROM coordinate_revision r LEFT JOIN app_user u ON u.user_id=r.applied_by_user_id
                 WHERE r.toilet_id=:toiletId ORDER BY r.applied_at DESC,r.coordinate_revision_id DESC LIMIT 1
                """, new MapSqlParameterSource("toiletId", toiletId),
                (rs, row) -> new Confirmation(time(rs.getTimestamp("applied_at") == null ? null : rs.getTimestamp("applied_at").toLocalDateTime()),
                        rs.getString("display_name")));
        return rows.isEmpty() ? new Confirmation(null, null) : rows.getFirst();
    }

    private Receipt latestReceipt(long reviewId) {
        List<Receipt> rows = jdbc.query("""
                SELECT execution_key,received_at,result,input_hash
                  FROM public_data_confirmed_receipt WHERE review_id=:id
                 ORDER BY received_at DESC,receipt_id DESC LIMIT 1
                """, values(reviewId), (rs, row) -> new Receipt(rs.getString("execution_key"),
                time(rs.getTimestamp("received_at").toLocalDateTime()), 0, rs.getString("result"), rs.getString("input_hash")));
        if (rows.isEmpty()) return null;
        Receipt value = rows.getFirst();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM public_data_confirmed_receipt WHERE review_id=:id", values(reviewId), Long.class);
        return new Receipt(value.batchExecutionKey(), value.receivedAt(), count == null ? 0 : count, value.result(), value.inputHash());
    }

    private List<DecisionHistory> decisions(long reviewId) {
        return jdbc.query("""
                SELECT d.action,d.decided_at,d.note,u.display_name
                  FROM public_data_change_decision d LEFT JOIN app_user u ON u.user_id=d.decided_by_user_id
                 WHERE d.review_id=:id ORDER BY d.decided_at DESC,d.decision_id DESC
                """, values(reviewId), (rs, row) -> new DecisionHistory(Action.valueOf(rs.getString("action")),
                time(rs.getTimestamp("decided_at").toLocalDateTime()),
                Objects.toString(rs.getString("display_name"), "관리자"), rs.getString("note")));
    }

    private Summary summary() {
        return jdbc.queryForObject("""
                SELECT SUM(status='PENDING') AS pending,
                       SUM(status='PENDING' AND first_received_at<:agedBefore) AS aged,
                       SUM(status='PENDING' AND (CONCAT(',',changed_fields,',') LIKE '%,LATITUDE,%'
                                                OR CONCAT(',',changed_fields,',') LIKE '%,LONGITUDE,%')) AS coordinate,
                       SUM(status='PENDING' AND (proposal_latitude IS NULL OR proposal_longitude IS NULL
                           OR (NULLIF(TRIM(proposal_road_address),'') IS NULL
                               AND NULLIF(TRIM(proposal_jibun_address),'') IS NULL))) AS warning
                  FROM public_data_change_review
                """, new MapSqlParameterSource("agedBefore", KoreanTime.now().minusHours(48)), (rs, row) ->
                new Summary(rs.getLong("pending"), rs.getLong("aged"), rs.getLong("coordinate"), rs.getLong("warning")));
    }

    private static ReviewItem reviewItem(ResultSet rs) throws SQLException {
        return new ReviewItem(rs.getLong("review_id"), rs.getLong("toilet_id"), rs.getString("name"),
                rs.getString("mng_no"), Status.valueOf(rs.getString("status")), fields(rs.getString("changed_fields")),
                time(rs.getTimestamp("first_received_at").toLocalDateTime()),
                time(rs.getTimestamp("last_received_at").toLocalDateTime()), rs.getLong("receipt_count"),
                rs.getBoolean("has_warning"), rs.getLong("version"));
    }

    private static DetailRow detailRow(ResultSet rs) throws SQLException {
        return new DetailRow(rs.getLong("review_id"), rs.getLong("toilet_id"), rs.getString("name"),
                rs.getString("mng_no"), Status.valueOf(rs.getString("status")), rs.getString("changed_fields"),
                local(rs, "first_received_at"), local(rs, "last_received_at"), rs.getLong("receipt_count"),
                rs.getLong("version"), rs.getString("baseline_hash"), rs.getBigDecimal("proposal_latitude"),
                rs.getBigDecimal("proposal_longitude"), rs.getString("proposal_road_address"),
                rs.getString("proposal_jibun_address"), local(rs, "provider_updated_at"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("road_address"),
                rs.getString("jibun_address"), rs.getString("data_source"), rs.getBoolean("has_warning"));
    }

    static String hash(BigDecimal latitude, BigDecimal longitude, String roadAddress, String jibunAddress) {
        String value = coordinate(latitude) + "\u001f" + coordinate(longitude) + "\u001f"
                + normalize(roadAddress) + "\u001f" + normalize(jibunAddress);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    private static List<ValidationIssue> validation(BigDecimal latitude, BigDecimal longitude,
                                                    String roadAddress, String jibunAddress) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (latitude == null || longitude == null)
            issues.add(new ValidationIssue("MISSING_COORDINATE", "제안 좌표가 없어 위치 변경으로 반영할 수 없습니다.", true));
        else if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0)
            issues.add(new ValidationIssue("INVALID_COORDINATE", "제안 좌표의 범위를 확인해 주세요.", true));
        if (normalize(roadAddress).isEmpty() && normalize(jibunAddress).isEmpty())
            issues.add(new ValidationIssue("MISSING_ADDRESS", "도로명 주소와 지번 주소가 모두 비어 있습니다.", true));
        return issues;
    }

    private static Long distance(BigDecimal lat1, BigDecimal lon1, BigDecimal lat2, BigDecimal lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return null;
        double firstLatitude = Math.toRadians(lat1.doubleValue());
        double secondLatitude = Math.toRadians(lat2.doubleValue());
        double deltaLatitude = secondLatitude - firstLatitude;
        double deltaLongitude = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());
        double value = Math.sin(deltaLatitude / 2) * Math.sin(deltaLatitude / 2)
                + Math.cos(firstLatitude) * Math.cos(secondLatitude)
                * Math.sin(deltaLongitude / 2) * Math.sin(deltaLongitude / 2);
        return Math.round(6_371_000 * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value)));
    }

    private static String coordinate(BigDecimal value) {
        return value == null ? "" : value.setScale(7, RoundingMode.HALF_UP).toPlainString();
    }
    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    private static List<String> fields(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(",")).filter(item -> !item.isBlank()).toList();
    }
    private static OffsetDateTime time(LocalDateTime value) { return value == null ? null : value.atOffset(SEOUL_OFFSET); }
    private static LocalDateTime local(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column); return timestamp == null ? null : timestamp.toLocalDateTime();
    }
    private static String escapeLike(String value) { return value.replace("!", "!!").replace("%", "!%").replace("_", "!_"); }
    private static void validatePage(int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100)
            throw new IllegalArgumentException("페이지는 0 이상, 크기는 1~100이어야 합니다.");
    }
    private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static MapSqlParameterSource values(long id) { return new MapSqlParameterSource("id", id); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    private record Candidate(long id, long toiletId, Status status, long version, String baselineHash,
                             BigDecimal proposalLatitude, BigDecimal proposalLongitude,
                             String proposalRoadAddress, String proposalJibunAddress,
                             BigDecimal currentLatitude, BigDecimal currentLongitude,
                             String currentRoadAddress, String currentJibunAddress) {}
    private record DetailRow(long id, long toiletId, String name, String managementNumber, Status status,
                             String changedFields, LocalDateTime firstReceivedAt, LocalDateTime lastReceivedAt,
                             long receiptCount, long version, String baselineHash,
                             BigDecimal proposalLatitude, BigDecimal proposalLongitude,
                             String proposalRoadAddress, String proposalJibunAddress,
                             LocalDateTime providerUpdatedAt, BigDecimal currentLatitude,
                             BigDecimal currentLongitude, String currentRoadAddress,
                             String currentJibunAddress, String dataSource, boolean hasWarning) {}
    private record Confirmation(OffsetDateTime at, String by) {}
    private record CurrentValue(BigDecimal latitude, BigDecimal longitude, String roadAddress, String jibunAddress) {}
}
