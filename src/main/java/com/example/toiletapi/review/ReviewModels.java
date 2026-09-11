package com.example.toiletapi.review;

import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

public final class ReviewModels {
    private ReviewModels() { }
    public record Create(@JsonDeserialize(using=ReviewJson.LongValue.class) Long toiletId,
                         @JsonDeserialize(using=ReviewJson.IntegerValue.class) Integer satisfaction,
                         @JsonDeserialize(using=ReviewJson.IntegerValue.class) Integer cleanliness,
                         @JsonDeserialize(using=ReviewJson.BooleanValue.class) Boolean paper,
                         @JsonDeserialize(using=ReviewJson.IntegerValue.class) Integer waitMinutes,
                         @JsonDeserialize(using=ReviewJson.StringValue.class) String comment, ReviewRules.Position position) {
        ReviewRules.Content content() { return new ReviewRules.Content(satisfaction, paper, cleanliness, waitMinutes, comment); }
    }
    public record Edit(@JsonDeserialize(using=ReviewJson.LongValue.class) Long version,
                       @JsonDeserialize(using=ReviewJson.IntegerValue.class) Integer satisfaction,
                       @JsonDeserialize(using=ReviewJson.IntegerValue.class) Integer cleanliness,
                       @JsonDeserialize(using=ReviewJson.BooleanValue.class) Boolean paper,
                       @JsonDeserialize(using=ReviewJson.IntegerValue.class) Integer waitMinutes,
                       @JsonDeserialize(using=ReviewJson.StringValue.class) String comment) {
        ReviewRules.Content content() { return new ReviewRules.Content(satisfaction, paper, cleanliness, waitMinutes, comment); }
    }
    public record Detach(@JsonDeserialize(using=ReviewJson.LongValue.class) Long version,
                         @JsonDeserialize(using=ReviewJson.BooleanValue.class) Boolean acknowledgeContentRetention) { }
    public record Item(String id, long toiletId, String toiletName, int satisfaction, int cleanliness,
                       boolean paper, int waitMinutes, String comment, long version, OffsetDateTime createdAt,
                       OffsetDateTime updatedAt, OffsetDateTime editableUntil, boolean canManage,
                       boolean authorRemoved, String authorDisplayName) { }
    public record Page(List<Item> items, String nextCursor, boolean hasMore) { }
    public record Detached(String id, String authorDisplayName, boolean contentRetained) { }
    /** Frequency check only; a new submission must still pass session, policy and location validation. */
    public record CreationStatus(boolean canCreate, String existingReviewId, OffsetDateTime nextAllowedAt) { }
    public record CreationConflict(String code, String message, String existingReviewId, OffsetDateTime nextAllowedAt) { }
    public record Summary(long count, Double rating, Double averageRating, Double paperPercent,
                          long paperSampleCount, Integer latestWaitMinutes, OffsetDateTime latestWaitAt) { }
}
