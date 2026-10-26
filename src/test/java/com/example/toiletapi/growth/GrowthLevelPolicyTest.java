package com.example.toiletapi.growth;

import com.geupddong.growth.GrowthLevelPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GrowthLevelPolicyTest {
    @Test void allLevelAndTierBoundariesKeepExistingMemberDefinition() {
        for(int level=1;level<=1000;level++) {
            long start=10L*(level*(long)level-1);
            assertEquals(start,GrowthService.threshold(level));assertEquals(level,GrowthService.level(start));
            if(level>1)assertEquals(level-1,GrowthService.level(start-1));
            String expected=level<=2?"white":level<=4?"green":level<=9?"yellow":level<=14?"blue":level<=24?"red":level<=39?"pink":"black";
            assertEquals(expected,GrowthLevelPolicy.tier(start).rank());
        }
    }
    @Test void largeNonnegativeLedgerBalancesCannotOverflowTheLevelLoop() {
        int actual=GrowthLevelPolicy.level(Long.MAX_VALUE);long units=Long.MAX_VALUE/10;
        assertTrue(actual*(long)actual-1<=units);assertTrue((actual+1)*(long)(actual+1)-1>units);
        assertEquals(1,GrowthLevelPolicy.level(0));
    }
}
