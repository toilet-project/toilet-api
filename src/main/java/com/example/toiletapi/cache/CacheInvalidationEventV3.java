package com.example.toiletapi.cache;

/** Coordinates are the envelope of every position held by one pending outbox event. */
public record CacheInvalidationEventV3(long toiletId, long revision, CacheInvalidationEvent.Action action,
                                       boolean catalogChanged, boolean regionScopeComplete, RegionBounds regionBounds) {
    private static final long MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;
    public CacheInvalidationEventV3 {
        if (toiletId < 1 || toiletId > MAX_SAFE_JSON_INTEGER || revision < 1 || revision > MAX_SAFE_JSON_INTEGER || action == null)
            throw new IllegalArgumentException("Invalid cache invalidation event");
    }

    public record RegionBounds(double west, double south, double east, double north) {
        public RegionBounds {
            if (!Double.isFinite(west) || !Double.isFinite(south) || !Double.isFinite(east) || !Double.isFinite(north)
                    || west < -180 || east > 180 || south < -90 || north > 90 || west > east || south > north)
                throw new IllegalArgumentException("Invalid region bounds");
        }
        public static RegionBounds point(double longitude, double latitude) {
            return new RegionBounds(longitude,latitude,longitude,latitude);
        }
        public RegionBounds include(double longitude, double latitude) {
            return new RegionBounds(Math.min(west,longitude),Math.min(south,latitude),
                    Math.max(east,longitude),Math.max(north,latitude));
        }
    }
}
