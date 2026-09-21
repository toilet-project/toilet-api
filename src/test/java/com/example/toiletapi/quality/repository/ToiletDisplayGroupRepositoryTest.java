package com.example.toiletapi.quality.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ToiletDisplayGroupRepositoryTest {

    @Test
    void existingGroupIncludesEachCurrentLocaleAndKeepsUngroupedMemberSeparate() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, true, true, false);
        when(rs.getLong("toilet_id")).thenReturn(101L, 101L, 101L, 102L);
        when(rs.getLong("group_id")).thenReturn(7L, 7L, 7L, 9L);
        when(rs.getString("group_name")).thenReturn("문화원", "문화원", "문화원", "중앙공원");
        when(rs.getString("translation_locale")).thenReturn("en", "ja", "zh-cn", null);
        when(rs.getString("translated_group_name")).thenReturn("Culture Center", "文化センター", "文化中心", null);

        when(jdbc.query(anyString(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Map<Long, ToiletDisplayGroupRepository.Assignment>>>any()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    assertTrue(sql.contains("BINARY tr.source_name = BINARY g.display_name"));
                    assertTrue(sql.contains("tr.manual_override = TRUE"));
                    return ((ResultSetExtractor<?>) invocation.getArgument(2)).extractData(rs);
                });

        var assignments = new ToiletDisplayGroupRepository(jdbc).assignmentsFor(List.of(101L, 102L));
        assertEquals(Map.of("en", "Culture Center", "ja", "文化センター", "zh-cn", "文化中心"),
                assignments.get(101L).translations());
        assertEquals("문화원", assignments.get(101L).displayName());
        assertTrue(assignments.get(102L).translations().isEmpty());
        assertFalse(assignments.containsKey(103L));
    }
}
