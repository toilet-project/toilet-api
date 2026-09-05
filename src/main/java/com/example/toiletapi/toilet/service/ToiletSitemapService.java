package com.example.toiletapi.toilet.service;

import java.util.List;
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

    public List<Long> shards() {
        // Index-only scan of the existing primary key. Never load entity/address/report data.
        var result = jdbc.queryForList("""
                SELECT (toilet_id - 1) DIV 10000 AS shard
                FROM toilet WHERE toilet_id BETWEEN 1 AND ?
                GROUP BY shard ORDER BY shard LIMIT 50000
                """, Long.class, MAX_ID);
        if (result.size() >= 50_000) throw new UnsupportedOperationException("Sitemap index capacity exceeded");
        return result;
    }

    public List<Long> ids(long shard) {
        if (shard < 0 || shard > (MAX_ID - 1) / SHARD_SIZE)
            throw new IllegalArgumentException("Invalid sitemap shard");
        long start = shard * SHARD_SIZE;
        long end = Math.min(start + SHARD_SIZE, MAX_ID);
        return jdbc.queryForList("""
                SELECT toilet_id FROM toilet WHERE toilet_id > ? AND toilet_id <= ?
                ORDER BY toilet_id LIMIT 10000
                """, Long.class, start, end);
    }
}
