package com.example.toiletapi.review;

@FunctionalInterface
public interface ReviewUnlinkProtection {
    void record(String reviewKey);
}
