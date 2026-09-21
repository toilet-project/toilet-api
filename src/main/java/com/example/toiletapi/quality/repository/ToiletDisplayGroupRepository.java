package com.example.toiletapi.quality.repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ToiletDisplayGroupRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public Map<Long, Assignment> assignmentsFor(Collection<Long> toiletIds) {
        if (toiletIds.isEmpty()) return Map.of();
        var parameters = new MapSqlParameterSource("toiletIds", toiletIds);
        Map<Long, Assignment> assignments = new LinkedHashMap<>();
        jdbc.query("""
                SELECT m.toilet_id, g.group_id, g.display_name,
                       en.display_name AS english_display_name
                  FROM toilet_display_group_member m
                  JOIN toilet_display_group g ON g.group_id = m.group_id
                  LEFT JOIN toilet_display_group_translation en
                    ON en.group_id = g.group_id AND en.locale = 'en'
                  JOIN toilet t ON t.toilet_id = m.toilet_id
                 WHERE m.toilet_id IN (:toiletIds)
                   AND t.latitude = g.latitude AND t.longitude = g.longitude
                """, parameters, (rs, rowNumber) -> Map.entry(rs.getLong("toilet_id"),
                new Assignment(rs.getLong("group_id"), rs.getString("display_name"),
                        rs.getString("english_display_name"))))
                .forEach(entry -> assignments.put(entry.getKey(), entry.getValue()));
        return assignments;
    }

    public List<Long> matchingToiletIds(Collection<Long> toiletIds, BigDecimal latitude, BigDecimal longitude) {
        if (toiletIds.isEmpty()) return List.of();
        var parameters = new MapSqlParameterSource("toiletIds", toiletIds)
                .addValue("latitude", latitude).addValue("longitude", longitude);
        return jdbc.queryForList("""
                SELECT toilet_id
                  FROM toilet
                 WHERE toilet_id IN (:toiletIds)
                   AND latitude = :latitude
                   AND longitude = :longitude
                 FOR UPDATE
                """, parameters, Long.class);
    }

    public boolean belongsToCoordinates(Long groupId, BigDecimal latitude, BigDecimal longitude) {
        var parameters = new MapSqlParameterSource("groupId", groupId)
                .addValue("latitude", latitude).addValue("longitude", longitude);
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM toilet_display_group
                 WHERE group_id = :groupId AND latitude = :latitude AND longitude = :longitude
                """, parameters, Long.class);
        return count != null && count == 1;
    }

    public boolean exists(Long groupId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM toilet_display_group WHERE group_id = :groupId",
                new MapSqlParameterSource("groupId", groupId), Long.class);
        return count != null && count == 1;
    }

    public List<Long> memberIds(Long groupId) {
        return jdbc.queryForList("""
                SELECT toilet_id
                  FROM toilet_display_group_member
                 WHERE group_id = :groupId
                 ORDER BY sort_order ASC, toilet_id ASC
                """, new MapSqlParameterSource("groupId", groupId), Long.class);
    }

    public Long create(String displayName, BigDecimal latitude, BigDecimal longitude, Long adminId) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.getJdbcOperations().update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO toilet_display_group
                        (display_name, latitude, longitude, created_by_user_id, updated_by_user_id)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, displayName);
            statement.setBigDecimal(2, latitude);
            statement.setBigDecimal(3, longitude);
            statement.setLong(4, adminId);
            statement.setLong(5, adminId);
            return statement;
        }, keyHolder);
        if (keyHolder.getKey() == null) throw new IllegalStateException("지도 노출 그룹을 생성하지 못했습니다.");
        return keyHolder.getKey().longValue();
    }

    public void update(Long groupId, String displayName, Long adminId) {
        jdbc.update("""
                UPDATE toilet_display_group
                   SET display_name = :displayName, updated_by_user_id = :adminId
                 WHERE group_id = :groupId
                """, new MapSqlParameterSource("groupId", groupId)
                .addValue("displayName", displayName).addValue("adminId", adminId));
    }

    public void saveEnglishDisplayName(Long groupId, String englishDisplayName) {
        if (englishDisplayName == null) {
            jdbc.update("""
                    DELETE FROM toilet_display_group_translation
                     WHERE group_id = :groupId AND locale = 'en'
                    """, new MapSqlParameterSource("groupId", groupId));
            return;
        }
        jdbc.update("""
                INSERT INTO toilet_display_group_translation
                    (group_id, locale, display_name, manual_override)
                VALUES (:groupId, 'en', :displayName, TRUE)
                ON DUPLICATE KEY UPDATE
                    display_name = VALUES(display_name), manual_override = TRUE, updated_at = CURRENT_TIMESTAMP
                """, new MapSqlParameterSource("groupId", groupId).addValue("displayName", englishDisplayName));
    }

    public void replaceMembers(Long groupId, List<Long> toiletIds) {
        var parameters = new MapSqlParameterSource("groupId", groupId).addValue("toiletIds", toiletIds);
        jdbc.update("""
                DELETE FROM toilet_display_group_member
                 WHERE group_id = :groupId OR toilet_id IN (:toiletIds)
                """, parameters);
        MapSqlParameterSource[] rows = new MapSqlParameterSource[toiletIds.size()];
        for (int index = 0; index < toiletIds.size(); index++) {
            rows[index] = new MapSqlParameterSource("groupId", groupId)
                    .addValue("toiletId", toiletIds.get(index)).addValue("sortOrder", index);
        }
        jdbc.batchUpdate("""
                INSERT INTO toilet_display_group_member (group_id, toilet_id, sort_order)
                VALUES (:groupId, :toiletId, :sortOrder)
                """, rows);
        deleteInvalidGroups();
    }

    public void delete(Long groupId) {
        int changed = jdbc.update("DELETE FROM toilet_display_group WHERE group_id = :groupId",
                new MapSqlParameterSource("groupId", groupId));
        if (changed == 0) throw new IllegalArgumentException("지도 노출 그룹을 찾을 수 없습니다.");
    }

    public void removeToilet(Long toiletId) {
        jdbc.update("DELETE FROM toilet_display_group_member WHERE toilet_id = :toiletId",
                new MapSqlParameterSource("toiletId", toiletId));
        deleteInvalidGroups();
    }

    private void deleteInvalidGroups() {
        jdbc.getJdbcOperations().update("""
                DELETE FROM toilet_display_group
                 WHERE group_id IN (
                    SELECT group_id FROM (
                        SELECT g.group_id
                          FROM toilet_display_group g
                          LEFT JOIN toilet_display_group_member m ON m.group_id = g.group_id
                         GROUP BY g.group_id
                        HAVING COUNT(m.toilet_id) < 2
                    ) invalid_groups
                 )
                """);
    }

    public record Assignment(Long groupId, String displayName, String englishDisplayName) {
        public Assignment(Long groupId, String displayName) {
            this(groupId, displayName, null);
        }

        public Map<String, String> translations() {
            return englishDisplayName == null || englishDisplayName.isBlank()
                    ? Map.of()
                    : Map.of("en", englishDisplayName);
        }
    }
}
