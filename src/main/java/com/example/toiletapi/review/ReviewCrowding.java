package com.example.toiletapi.review;

import java.time.OffsetDateTime;

/** Recent review tendency, never an occupancy sensor or a real-time guarantee. */
public record ReviewCrowding(String status,Integer waitLowerBound,long sampleCount,long zeroWaitCount,
                             Double averageWaitMinutes,int windowDays,OffsetDateTime latestReviewAt) {
    public static ReviewCrowding of(long count,long zero,long sum,OffsetDateTime latest) {
        if(count==0)return new ReviewCrowding("UNKNOWN",null,0,0,null,7,null);
        double average=(double)sum/count;
        String status;Integer bound;
        if(average<5) {status="CLEAR";bound=0;}
        else {status="WAIT";bound=(int)Math.floor(average/5)*5;}
        return new ReviewCrowding(status,bound,count,zero,Math.round(average*10)/10.0,7,latest);
    }
}
