package com.example.toiletapi.publicdatareview;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class PublicDataChangeReviewModels {
    private PublicDataChangeReviewModels() {}

    public enum Status { PENDING, APPLIED, KEPT_CURRENT, SUPERSEDED }
    public enum Action { APPLY, KEEP_CURRENT, DEFER }

    public record Page(List<ReviewItem> items, int page, int size, long totalElements,
                       int totalPages, Summary summary) {}
    public record Summary(long pending, long aged, long coordinate, long warning) {}
    public record ReviewItem(long id, long toiletId, String name, String managementNumber,
                             Status status, List<String> changedFields, OffsetDateTime firstReceivedAt,
                             OffsetDateTime lastReceivedAt, long receiptCount, boolean hasWarning,
                             long version) {}
    public record ProtectedValue(BigDecimal latitude, BigDecimal longitude, String roadAddress,
                                 String jibunAddress, OffsetDateTime confirmedAt, String confirmedBy) {}
    public record Proposal(BigDecimal latitude, BigDecimal longitude, String roadAddress,
                           String jibunAddress, OffsetDateTime providerUpdatedAt) {}
    public record Receipt(String batchExecutionKey, OffsetDateTime receivedAt, long receiptCount,
                          String result, String inputHash) {}
    public record ValidationIssue(String code, String message, boolean blocking) {}
    public record Validation(List<ValidationIssue> issues) {}
    public record DecisionHistory(Action action, OffsetDateTime decidedAt, String actorName, String note) {}
    public record HiddenContext(long eventId, Long representativeToiletId, String reason,
                                OffsetDateTime hiddenAt, String currentVisibility,
                                String baselineName, String currentName, String proposalName) {}
    public record Detail(ReviewItem review, String dataSource, String baselineHash, boolean isStale,
                         ProtectedValue current, Proposal proposal, Long distanceMeters, Receipt receipt,
                         Validation validation, List<DecisionHistory> decisionHistory, HiddenContext hiddenContext) {
        public Detail(ReviewItem review,String dataSource,String baselineHash,boolean isStale,ProtectedValue current,
                      Proposal proposal,Long distanceMeters,Receipt receipt,Validation validation,List<DecisionHistory> history) {
            this(review,dataSource,baselineHash,isStale,current,proposal,distanceMeters,receipt,validation,history,null);
        }
    }
    public record DecisionRequest(@NotNull Action action, @Size(max = 500) String note,
                                  @NotNull Long expectedVersion, @NotNull @Size(min = 64, max = 64)
                                  String expectedBaselineHash) {}
}
