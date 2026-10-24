package com.example.toiletapi.review;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.growth.GrowthService;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Permanent, explicitly confirmed moderation. Audit and growth failures roll the deletion back. */
@Service
public class AdminReviewModerationService {
    public static final String POLICY_VERSION="review-moderation-v1";
    public enum Reason { SPAM, ABUSE, PERSONAL_INFORMATION, IRRELEVANT, MANIPULATION }
    private final AdminReviewAccess access;
    private final AdminReviewRepository repository;
    private final GrowthService growth;
    private final AuditLogService audit;
    public AdminReviewModerationService(AdminReviewAccess access,AdminReviewRepository repository,GrowthService growth,AuditLogService audit) {
        this.access=access;this.repository=repository;this.growth=growth;this.audit=audit;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public AdminReviewModels.Removed remove(Jwt jwt,long id,AdminReviewModels.Remove request) {
        long actor=access.requireAdminForMutation(jwt);
        Reason reason=validate(id,request);
        var initial=repository.removalTarget(id,false).orElseThrow(AdminReviewModerationService::notFound);
        // Member edits/unlinks acquire the author row before the review row. Keep that order.
        repository.lockRemovalAuthor(initial.authorId());
        var current=repository.removalTarget(id,true).orElseThrow(AdminReviewModerationService::notFound);
        if(!current.reviewKey().equals(request.reviewKey()) || current.version()!=request.version())throw changed();
        if(repository.remove(id,current.reviewKey(),current.version())!=1)throw changed();
        // Cascades remove submission/exclusion rows; the independent 24-hour write guard is preserved.
        growth.reconcileAfterReviewRemoval(current.authorId(),current.reviewKey());
        audit.record(actor,AuditAction.REVIEW_DELETED,"TOILET_REVIEW",id,
                Map.of("reason",reason.name(),"policyVersion",POLICY_VERSION,"reviewKey",current.reviewKey()));
        return new AdminReviewModels.Removed(Long.toString(id),true);
    }
    private static Reason validate(long id,AdminReviewModels.Remove request) {
        try {
            if(id<=0 || request==null || request.version()==null || request.version()<0 || !Boolean.TRUE.equals(request.confirmed())
                    || request.reviewKey()==null || !UUID.fromString(request.reviewKey()).toString().equals(request.reviewKey()))throw new IllegalArgumentException();
            return Reason.valueOf(request.reason());
        } catch(RuntimeException invalid) {throw new IllegalArgumentException("삭제 사유와 확인 내용을 선택하고 최신 리뷰에서 다시 시도해 주세요.");}
    }
    private static ReviewFailure changed() {return new ReviewFailure(409,"REVIEW_CHANGED","리뷰가 변경되었습니다. 최신 내용을 다시 확인해 주세요.");}
    private static ReviewFailure notFound() {return new ReviewFailure(404,"REVIEW_NOT_FOUND","이미 삭제되었거나 리뷰를 찾을 수 없습니다.");}
}
