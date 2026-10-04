package com.example.toiletapi.growth;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.model.AuditLog;
import com.example.toiletapi.auth.repository.AuditLogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminGrowthOperations {
    private final AdminGrowthAccess access;
    private final GrowthService growth;
    private final AuditLogRepository audit;
    public AdminGrowthOperations(AdminGrowthAccess access, GrowthService growth, AuditLogRepository audit) {
        this.access=access;this.growth=growth;this.audit=audit;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GrowthService.PolicyPreview initialize(Jwt jwt, String expectedVersion) {
        long actor=access.requireAdmin(jwt);
        requireVersion(expectedVersion,growth.previewPolicy().version());
        var result=growth.initializePolicy(true);
        audit.save(AuditLog.record(actor,AuditAction.GROWTH_POLICY_INITIALIZED,"GROWTH_POLICY",null,versionJson(expectedVersion)));
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GrowthService.BackfillResult apply(Jwt jwt, long userId, String expectedVersion) {
        long actor=access.requireAdmin(jwt);requireId(userId);
        requireVersion(expectedVersion,growth.previewUser(userId).policyVersion());
        var result=growth.backfillUser(userId,true);
        // USER targets are redacted by the existing final account-erasure path.
        audit.save(AuditLog.record(actor,AuditAction.GROWTH_BACKFILL_APPLIED,"USER",userId,versionJson(expectedVersion)));
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GrowthService.ExclusionResult eligibility(Jwt jwt,long reviewId,Boolean excluded,String reason) {
        long actor=access.requireAdmin(jwt);requireId(reviewId);
        if(excluded==null || reason==null || reason.isBlank() || reason.length()>200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"제외 여부와 사유를 입력해 주세요. 사유는 200자 이하여야 합니다.");
        }
        var result=growth.setReviewExcluded(reviewId,excluded,reason.strip());
        // Do not duplicate the review owner, facility, text or location into audit metadata.
        audit.save(AuditLog.record(actor,excluded?AuditAction.GROWTH_REVIEW_EXCLUDED:AuditAction.GROWTH_REVIEW_RESTORED,
                "TOILET_REVIEW",reviewId,"{\"excluded\":"+excluded+"}"));
        return result;
    }

    static void requireId(long id) {
        if(id<=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"대상을 확인해 주세요.");
    }
    private static void requireVersion(String expected,String actual) {
        if(expected==null || !expected.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"미리 확인한 수집 기준 버전이 필요합니다.");
        }
        if(!expected.equals(actual))throw new ResponseStatusException(HttpStatus.CONFLICT,"수집 기준이 변경됐어요. 예상 결과를 다시 확인해 주세요.");
    }
    private static String versionJson(String version) { return "{\"policyVersion\":\""+version+"\"}"; }
}
