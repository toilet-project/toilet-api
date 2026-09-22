package com.example.toiletapi.toilet.service;

import java.util.List;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public ID-only projection; fixed ranges avoid OFFSET drift on insertion/deletion. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ToiletSitemapService {
    public static final long MAX_ID = 9_007_199_254_740_991L;
    public static final int SHARD_SIZE = 10_000;
    private final JdbcTemplate jdbc;

    public record SitemapEntry(long id, String name, BigDecimal latitude, BigDecimal longitude) {}

    private static String locale(String raw) {
        return switch (raw) {
            case "ko", "en", "ja", "zh-cn", "zh-tw", "zh-hk" -> raw;
            default -> throw new IllegalArgumentException("Unsupported sitemap locale");
        };
    }

    private static long start(long shard) {
        if (shard < 0 || shard > (MAX_ID - 1) / SHARD_SIZE)
            throw new IllegalArgumentException("Invalid sitemap shard");
        return shard * SHARD_SIZE;
    }

    public List<Long> shards() {
        // Visibility-filtered ID projection. Never load entity/address/report data.
        var result = jdbc.queryForList("""
                SELECT (toilet_id - 1) DIV 10000 AS shard
                FROM toilet WHERE visibility_status='VISIBLE' AND toilet_id BETWEEN 1 AND ?
                GROUP BY shard ORDER BY shard LIMIT 50000
                """, Long.class, MAX_ID);
        if (result.size() >= 50_000) throw new UnsupportedOperationException("Sitemap index capacity exceeded");
        return result;
    }

    public List<Long> ids(long shard) {
        long start = start(shard);
        long end = Math.min(start + SHARD_SIZE, MAX_ID);
        return jdbc.queryForList("""
                SELECT toilet_id FROM toilet WHERE visibility_status='VISIBLE' AND toilet_id > ? AND toilet_id <= ?
                ORDER BY toilet_id LIMIT 10000
                """, Long.class, start, end);
    }

    /** Only current, named translations with a translated address can enter a localized sitemap. */
    public List<Long> localizedShards(String rawLocale) {
        String locale = locale(rawLocale);
        if ("ko".equals(locale)) return shards();
        var result = jdbc.queryForList("""
                SELECT (t.toilet_id - 1) DIV 10000 AS shard
                  FROM toilet t
                  JOIN toilet_translation tr ON tr.toilet_id=t.toilet_id AND tr.locale=?
                  JOIN toilet_translation ko ON ko.toilet_id=t.toilet_id AND ko.locale='ko'
                 WHERE t.visibility_status='VISIBLE' AND t.toilet_id BETWEEN 1 AND ?
                   AND tr.source_hash=ko.source_hash
                   AND NULLIF(TRIM(tr.name),'') IS NOT NULL
                   AND (NULLIF(TRIM(tr.road_address),'') IS NOT NULL
                        OR NULLIF(TRIM(tr.jibun_address),'') IS NOT NULL)
                 GROUP BY shard ORDER BY shard LIMIT 50000
                """, Long.class, locale, MAX_ID);
        if (result.size() >= 50_000) throw new UnsupportedOperationException("Sitemap index capacity exceeded");
        return result;
    }

    public List<SitemapEntry> entries(long shard, String rawLocale) {
        String locale = locale(rawLocale);
        long start = start(shard);
        long end = Math.min(start + SHARD_SIZE, MAX_ID);
        if ("ko".equals(locale)) return jdbc.query("""
                SELECT toilet_id AS id,name,latitude,longitude FROM toilet
                 WHERE visibility_status='VISIBLE' AND toilet_id>? AND toilet_id<=?
                 ORDER BY toilet_id LIMIT 10000
                """, (rs, row) -> new SitemapEntry(rs.getLong("id"), rs.getString("name"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude")), start, end);
        return jdbc.query("""
                SELECT t.toilet_id AS id,tr.name,t.latitude,t.longitude
                  FROM toilet t
                  JOIN toilet_translation tr ON tr.toilet_id=t.toilet_id AND tr.locale=?
                  JOIN toilet_translation ko ON ko.toilet_id=t.toilet_id AND ko.locale='ko'
                 WHERE t.visibility_status='VISIBLE' AND t.toilet_id>? AND t.toilet_id<=?
                   AND tr.source_hash=ko.source_hash
                   AND NULLIF(TRIM(tr.name),'') IS NOT NULL
                   AND (NULLIF(TRIM(tr.road_address),'') IS NOT NULL
                        OR NULLIF(TRIM(tr.jibun_address),'') IS NOT NULL)
                 ORDER BY t.toilet_id LIMIT 10000
                """, (rs, row) -> new SitemapEntry(rs.getLong("id"), rs.getString("name"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude")), locale, start, end);
    }
}
