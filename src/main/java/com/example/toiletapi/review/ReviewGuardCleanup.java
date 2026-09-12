package com.example.toiletapi.review;

import java.time.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Bounded expiry of throttle metadata ONLY. No review, author, report or audit content is deleted. */
@Component @ConditionalOnProperty(name="reviews.guard-cleanup-enabled",havingValue="true")
public class ReviewGuardCleanup {
    private final JdbcTemplate jdbc;private final Clock clock;
    public ReviewGuardCleanup(JdbcTemplate jdbc,@Qualifier("reviewClock") Clock clock){this.jdbc=jdbc;this.clock=clock;}
    @Scheduled(fixedDelay=300000,initialDelay=300000) @Transactional
    public void scheduled(){deleteExpired();}
    public int deleteExpired(){
        var cutoff=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.ofHours(9));
        var keys=jdbc.query("SELECT user_id,toilet_id FROM toilet_review_toilet_guard WHERE next_allowed_at<=? ORDER BY next_allowed_at,user_id,toilet_id LIMIT 1000",
                (rs,n)->new long[]{rs.getLong(1),rs.getLong(2)},cutoff);
        int removed=0;
        for(var key:keys)removed+=jdbc.update("DELETE FROM toilet_review_toilet_guard WHERE user_id=? AND toilet_id=? AND next_allowed_at<=?",key[0],key[1],cutoff);
        return removed;
    }
}
