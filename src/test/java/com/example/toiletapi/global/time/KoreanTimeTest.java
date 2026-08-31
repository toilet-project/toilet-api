package com.example.toiletapi.global.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class KoreanTimeTest {

    @Test
    void createsCurrentTimeInAsiaSeoul() {
        LocalDateTime before = LocalDateTime.now(KoreanTime.ZONE_ID);
        LocalDateTime actual = KoreanTime.now();
        LocalDateTime after = LocalDateTime.now(KoreanTime.ZONE_ID);

        assertThat(actual).isBetween(before, after);
        assertThat(KoreanTime.ZONE_ID.getId()).isEqualTo("Asia/Seoul");
    }
}
