package com.example.toiletapi.review;

import java.time.OffsetDateTime;
import java.util.List;

/** Read-only moderation view; deliberately excludes account identifiers and contact details. */
public final class AdminReviewModels {
    private AdminReviewModels() { }
    public record Item(String id, long toiletId, String toiletName, String toiletVisibility,
                       int satisfaction, int cleanliness, boolean paper, int waitMinutes,
                       String authorDisplayName, boolean authorRemoved, OffsetDateTime createdAt,
                       OffsetDateTime updatedAt, boolean hasComment) { }
    public record Page(List<Item> items, String nextCursor, boolean hasMore) { }
    public record Detail(String id, long toiletId, String toiletName, String toiletVisibility,
                         int satisfaction, int cleanliness, boolean paper, int waitMinutes,
                         String authorDisplayName, boolean authorRemoved, OffsetDateTime createdAt,
                         OffsetDateTime updatedAt, boolean hasComment, String comment) { }
}
