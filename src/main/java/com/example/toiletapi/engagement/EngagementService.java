package com.example.toiletapi.engagement;

import com.example.toiletapi.policy.service.PolicyConsentService;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class EngagementService {
    public record Counts(long toiletId,long views,long likes) { }
    public record ViewRequest(String sessionId,String eventId) { }
    public record ViewResult(boolean counted,Counts counts,OffsetDateTime nextEligibleAt) { }
    public record LikeState(long toiletId,boolean liked,long likes) { }
    public record Actor(long id,long authVersion) { }
    private final EngagementRepository repository;
    private final PolicyConsentService policies;
    private final EngagementConfiguration.Settings settings;
    private final Clock clock;
    public EngagementService(EngagementRepository repository,PolicyConsentService policies,EngagementConfiguration.Settings settings,
                             @Qualifier("engagementClock") Clock clock) { this.repository=repository;this.policies=policies;this.settings=settings;this.clock=clock; }
    @Transactional(readOnly=true)
    public Counts counts(long toilet) { facility(toilet);return repository.counts(toilet); }
    public ViewResult view(long toilet,ViewRequest request,boolean bot) {
        facility(toilet);
        if(request==null)throw invalid();
        String session=hash("session:"+uuid(request.sessionId())),event=hash("event:"+uuid(request.sessionId())+":"+uuid(request.eventId()));
        if(bot)return new ViewResult(false,repository.counts(toilet),null);
        LocalDateTime now=now();
        var last=repository.lockGuard(session,toilet,now.plusDays(1));
        var receipt=repository.lockReceipt(event,toilet,now.plusDays(1));
        if(receipt.toilet()!=toilet)throw new EngagementFailure(409,"EVENT_REUSED","다른 화장실에 같은 조회 요청을 사용할 수 없습니다.");
        if(receipt.processed()) {
            return new ViewResult(false,repository.counts(toilet),last==null?null:last.plusMinutes(30).atOffset(ZoneOffset.ofHours(9)));
        }
        boolean counted=last==null || !now.isBefore(last.plusMinutes(30));
        if(counted)repository.increment(session,toilet,now);
        repository.remember(event);
        return new ViewResult(counted,repository.counts(toilet),(counted?now:last).plusMinutes(30).atOffset(ZoneOffset.ofHours(9)));
    }
    public LikeState mine(long toilet,Actor actor) { authorize(actor);facility(toilet);return state(toilet,actor); }
    public LikeState setLike(long toilet,Actor actor,boolean liked) {
        authorize(actor);facility(toilet);
        if(liked)repository.like(actor.id(),toilet,now());else repository.unlike(actor.id(),toilet);
        return state(toilet,actor);
    }
    private LikeState state(long toilet,Actor actor) { return new LikeState(toilet,repository.liked(actor.id(),toilet),repository.counts(toilet).likes()); }
    private void authorize(Actor actor) {
        settings.requireEnabled();
        if(actor==null || actor.id()<=0)throw unauthorized();
        var account=repository.lockUser(actor.id()).orElseThrow(EngagementService::unauthorized);
        if(account.version()!=actor.authVersion())throw unauthorized();
        if(!"ACTIVE".equals(account.status()))throw new EngagementFailure(403,"ACCOUNT_UNAVAILABLE","좋아요를 이용할 수 없는 계정입니다.");
        policies.requireEligibleUser(actor.id());
    }
    private void facility(long toilet) { settings.requireEnabled();if(toilet<=0 || !repository.visible(toilet))throw new EngagementFailure(404,"TOILET_NOT_FOUND","화장실을 찾을 수 없습니다."); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.ofHours(9)); }
    String hash(String value) {
        try { var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(settings.secret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(("toilet-engagement-v1:"+value).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String uuid(String value) { try { if(value==null || !UUID.fromString(value).toString().equals(value))throw invalid();return value; } catch(IllegalArgumentException e) {throw invalid();} }
    private static EngagementFailure invalid() { return new EngagementFailure(400,"INVALID_EVENT","조회 요청을 확인해 주세요."); }
    private static EngagementFailure unauthorized() { return new EngagementFailure(401,"AUTHENTICATION_REQUIRED","로그인 후 좋아요를 눌러 주세요."); }
}
