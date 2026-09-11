package com.example.toiletapi.review;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.example.toiletapi.review.ReviewRules.*;

class ReviewRulesTest {
    private static final Instant NOW=Instant.parse("2026-09-11T00:00:00Z");
    private static final LocationPolicy POLICY=new LocationPolicy(150,50);
    @Test void requiredAndOptionalFields() {
        assertEquals("",ReviewRules.validate(new Content(5,false,1,null,null)).comment());
        assertEquals(0,ReviewRules.validate(new Content(5,false,1,null,null)).waitMinutes());
        for(Integer score:new Integer[]{null,0,6}) {
            assertThrows(IllegalArgumentException.class,()->ReviewRules.validate(new Content(score,true,5,0,"")));
            assertThrows(IllegalArgumentException.class,()->ReviewRules.validate(new Content(5,true,score,0,"")));
        }
        assertThrows(IllegalArgumentException.class,()->ReviewRules.validate(new Content(5,null,5,0,"")));
    }
    @Test void waitDefaultsToZeroAndOnlyAllowsTenMinuteSteps() {
        for(int wait=0;wait<=60;wait+=10)assertEquals(wait,ReviewRules.validate(new Content(5,true,5,wait,"")).waitMinutes());
        for(int wait:new int[]{-10,1,65,70})assertThrows(IllegalArgumentException.class,()->ReviewRules.validate(new Content(5,true,5,wait,"")));
    }
    @Test void unicodeLimitDoesNotCountEmojiAsTwoCharacters() {
        assertEquals("😀".repeat(200),ReviewRules.validate(new Content(5,true,5,0,"😀".repeat(200))).comment());
        assertThrows(IllegalArgumentException.class,()->ReviewRules.validate(new Content(5,true,5,0,"가".repeat(201))));
        assertThrows(IllegalArgumentException.class,()->ReviewRules.validate(new Content(5,true,5,0,"a\u0000")));
        assertThrows(IllegalArgumentException.class,()->ReviewRules.validate(new Content(5,true,5,0,String.valueOf((char)0xD800))));
    }
    @Test void distanceAccuracyAndFreshnessAreIndependentGates() {
        assertEquals(0,ReviewRules.requireNearby(new Position(36.3,127.3,50.0,NOW.minusSeconds(300)),36.3,127.3,NOW,POLICY));
        for(double meters:new double[]{150.001,151,200})assertThrows(IllegalArgumentException.class,()->near(meters));
        assertTrue(near(149.999)<150);
        for(Double accuracy:new Double[]{null,-1.0,50.001,Double.NaN,Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->ReviewRules.requireNearby(new Position(36.3,127.3,accuracy,NOW),36.3,127.3,NOW,POLICY));
        for(Instant stamp:new Instant[]{NOW.minusSeconds(300).minusMillis(1),NOW.plusSeconds(5).plusMillis(1)})
            assertThrows(IllegalArgumentException.class,()->ReviewRules.requireNearby(new Position(36.3,127.3,10.0,stamp),36.3,127.3,NOW,POLICY));
        assertThrows(IllegalArgumentException.class,()->ReviewRules.requireNearby(new Position(null,127.3,10.0,NOW),36.3,127.3,NOW,POLICY));
        assertThrows(IllegalArgumentException.class,()->ReviewRules.requireNearby(new Position(Double.NaN,127.3,10.0,NOW),36.3,127.3,NOW,POLICY));
        assertThrows(IllegalArgumentException.class,()->ReviewRules.requireNearby(new Position(36.3,127.3,10.0,NOW),null,127.3,NOW,POLICY));
    }
    private double near(double meters) {return ReviewRules.requireNearby(new Position(36.3+Math.toDegrees(meters/6371000),127.3,10.0,NOW),36.3,127.3,NOW,POLICY);}
    @Test void sevenDayDeadlineCannotBeExtendedByEditsOrAnonymousAccess() {
        assertTrue(ReviewRules.canManage(1L,1,NOW,NOW.plusSeconds(604799)));
        assertFalse(ReviewRules.canManage(1L,1,NOW,NOW.plusSeconds(604800)));
        assertFalse(ReviewRules.canManage(1L,2,NOW,NOW));
        assertFalse(ReviewRules.canManage(null,1,NOW,NOW));
        assertFalse(ReviewRules.canManage(1L,1,NOW,NOW.minusSeconds(1)));
    }
}
