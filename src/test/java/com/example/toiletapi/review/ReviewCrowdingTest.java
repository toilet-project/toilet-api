package com.example.toiletapi.review;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ReviewCrowdingTest {
    @Test void noReviewsAndStrictMajority() {
        assertEquals("UNKNOWN",ReviewCrowding.of(0,0,0,null).status());
        assertEquals("CLEAR",ReviewCrowding.of(3,2,10,null).status());
        assertEquals("WAIT",ReviewCrowding.of(2,1,10,null).status());
        assertEquals("UNDER_FIVE",ReviewCrowding.of(3,1,10,null).status());
    }
    @Test void fiveMinuteFloorAndHighAverageOverridesZeroMajority() {
        assertEquals(10,ReviewCrowding.of(5,3,60,null).waitLowerBound());
        assertEquals(10,ReviewCrowding.of(2,0,20,null).waitLowerBound());
        assertEquals(15,ReviewCrowding.of(2,0,30,null).waitLowerBound());
        assertEquals(15,ReviewCrowding.of(3,0,50,null).waitLowerBound());
        assertEquals(60,ReviewCrowding.of(1,0,60,null).waitLowerBound());
    }
}
