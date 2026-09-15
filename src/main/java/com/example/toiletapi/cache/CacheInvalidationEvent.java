package com.example.toiletapi.cache;

public record CacheInvalidationEvent(long toiletId, long revision, Action action, boolean catalogChanged) {
    private static final long MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;
    public CacheInvalidationEvent {
        if (toiletId < 1 || toiletId > MAX_SAFE_JSON_INTEGER || revision < 1 || revision > MAX_SAFE_JSON_INTEGER || action == null)
            throw new IllegalArgumentException("Invalid cache invalidation event");
    }
    public enum Action { UPSERT, DELETE, PRIVATE }
}
