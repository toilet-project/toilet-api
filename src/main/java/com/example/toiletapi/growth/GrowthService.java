package com.example.toiletapi.growth;

import com.example.toiletapi.policy.service.PolicyConsentService;
import com.geupddong.growth.GrowthLedger;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Private XP ledger and reproducible projections from currently linked, eligible reviews. */
@Service
public class GrowthService {
    public static final String POLICY_VERSION = "2026-10-v1";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int REVIEW_XP = 10, CHECKIN_XP = 2, DISTRICT_XP = 20;
    private static final int BRONZE_XP = 30, SILVER_XP = 50, GOLD_XP = 100;
    private static final int BRONZE_FACILITIES = 3, SILVER_FACILITIES = 10, GOLD_FACILITIES = 30;
    private final JdbcTemplate jdbc;
    private final PolicyConsentService policies;
    private final boolean enabled;
    private final Clock clock;

    public record Actor(long userId, long authVersion) { }
    public record Badge(String type, String code, String name, String tier, int xp, LocalDateTime earnedAt) { }
    public record Region(String code, String name, int targetDistricts, int earnedDistricts,
                         int distinctFacilities, String tier, int bronzeFacilities, int silverFacilities,
                         int goldFacilities, int silverDistricts, int goldDistricts) { }
    public record Summary(boolean enabled, String policyVersion, long totalXp, int level, String rank,
                          String rankName, int nextLevel, long nextLevelXp, long remainingXp,
                          int progressPercent, boolean checkInAvailable, List<Badge> badges,
                          List<Region> regions) { }
    public record HistoryItem(long id, int deltaXp, String type, String reason, LocalDateTime happenedAt) { }
    public record History(List<HistoryItem> items) { }
    public record PolicyPreview(String version, boolean initialized, int targetDistricts, int regions) { }
    public record Preview(long userId, String policyVersion, int eligibleReviews, int distinctFacilities,
                          int pendingRegionFacilities, long expectedXp, long currentXp, long deltaXp,
                          int expectedAwards, int linkedReviews, Map<String,Integer> excludedReasons) { }
    public record ReconcileResult(long userId, boolean applied, int earnedCount, int revokedCount, long totalXp) { }
    public record BackfillResult(boolean applied, Preview preview, ReconcileResult reconcile) { }
    public record ExclusionResult(long reviewId, boolean excluded, ReconcileResult reconcile) { }

    private record User(String status, long authVersion) { }
    private record ReviewOwner(String key, Long userId) { }
    private record ReviewCounts(int linked, int eligible, Map<String,Integer> excludedReasons) { }
    private record Target(String code, String sidoCode, String sidoName, String displayName) { }
    private record Rules(String version, int reviewXp, int checkinXp, int districtXp,
                         int bronzeXp, int silverXp, int goldXp, int bronzeFacilities,
                         int silverFacilities, int goldFacilities, int silverPercent, int goldPercent) { }
    private record Review(String key, long toiletId, String sigunguCode) { }
    private record Award(long id, String kind, String key, String version, int amount, boolean active,
                         LocalDateTime awardedAt) { }
    private record Desired(String kind, String key, String version, int amount) { }
    private record Projection(Map<String, Desired> awards, int eligibleReviews, int facilities,
                              int pendingRegionFacilities, Map<String, Integer> facilityCounts,
                              Map<String, Set<String>> districtCounts) { }

    @Autowired
    public GrowthService(JdbcTemplate jdbc, PolicyConsentService policies,
                         @Value("${growth.enabled:false}") boolean enabled) {
        this(jdbc, policies, enabled, Clock.systemUTC());
    }

    public GrowthService(JdbcTemplate jdbc, PolicyConsentService policies, boolean enabled, Clock clock) {
        this.jdbc = jdbc; this.policies = policies; this.enabled = enabled; this.clock = clock;
    }

    /** For offline backup replay after the corresponding review author links have been removed. */
    public static GrowthService offline(JdbcTemplate jdbc) {
        return new GrowthService(jdbc, null, true, Clock.systemUTC());
    }

    @Transactional(readOnly = true)
    public PolicyPreview previewPolicy() {
        var frozen = policy(POLICY_VERSION);
        List<Target> targets = frozen == null ? currentTargets() : targets(POLICY_VERSION);
        return new PolicyPreview(POLICY_VERSION, frozen != null, targets.size(),
                (int) targets.stream().map(Target::sidoCode).distinct().count());
    }

    /** apply=false is a pure read. A second apply retains the first frozen target list. */
    @Transactional
    public PolicyPreview initializePolicy(boolean apply) {
        if (!apply) return previewPolicy();
        if (policy(POLICY_VERSION) != null) return previewPolicy();
        List<Target> targets = currentTargets();
        if (targets.isEmpty()) throw new GrowthFailure(409,"GROWTH_TARGETS_UNAVAILABLE","검증된 지역 수집 대상이 없습니다.");
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO growth_policy_snapshot(policy_version,initialized_at,target_count,review_xp,checkin_xp,
                    district_xp,bronze_xp,silver_xp,gold_xp,bronze_facilities,silver_facilities,gold_facilities,
                    silver_coverage_percent,gold_coverage_percent)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, POLICY_VERSION, now, targets.size(), REVIEW_XP, CHECKIN_XP, DISTRICT_XP,
                BRONZE_XP, SILVER_XP, GOLD_XP, BRONZE_FACILITIES, SILVER_FACILITIES,
                GOLD_FACILITIES, 50, 100);
        for (Target target : targets) jdbc.update("""
                INSERT INTO growth_policy_target(policy_version,sigungu_code,sido_code,sido_name,display_name)
                VALUES(?,?,?,?,?)
                """, POLICY_VERSION, target.code(), target.sidoCode(), target.sidoName(), target.displayName());
        return previewPolicy();
    }

    private List<Target> currentTargets() {
        return GrowthLedger.currentTargets(jdbc).stream()
                .map(t->new Target(t.code(),t.sidoCode(),t.sidoName(),t.displayName())).toList();
    }

    private List<Target> targets(String version) {
        return jdbc.query("""
                SELECT sigungu_code,sido_code,sido_name,display_name
                FROM growth_policy_target WHERE policy_version=? ORDER BY sido_code,sigungu_code
                """, (rs, i) -> new Target(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), version);
    }

    private Rules policy(String version) {
        return jdbc.query("""
                SELECT policy_version,review_xp,checkin_xp,district_xp,bronze_xp,silver_xp,gold_xp,
                       bronze_facilities,silver_facilities,gold_facilities,silver_coverage_percent,gold_coverage_percent
                FROM growth_policy_snapshot WHERE policy_version=?
                """, (rs, i) -> new Rules(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10),
                rs.getInt(11), rs.getInt(12)), version).stream().findFirst().orElse(null);
    }

    private Rules previewRules() {
        return new Rules(POLICY_VERSION, REVIEW_XP, CHECKIN_XP, DISTRICT_XP, BRONZE_XP,
                SILVER_XP, GOLD_XP, BRONZE_FACILITIES, SILVER_FACILITIES, GOLD_FACILITIES, 50, 100);
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), KST); }
    private LocalDate today() { return LocalDate.ofInstant(clock.instant(), KST); }

    private User user(long userId, boolean lock) {
        if(userId<=0) throw new GrowthFailure(401,"AUTHENTICATION_REQUIRED","로그인을 다시 확인해 주세요.");
        return jdbc.query("SELECT status,auth_version FROM app_user WHERE user_id=?"+(lock?" FOR UPDATE":""),
                (rs,i)->new User(rs.getString(1),rs.getLong(2)),userId).stream().findFirst()
                .orElseThrow(()->new GrowthFailure(401,"AUTHENTICATION_REQUIRED","로그인을 다시 확인해 주세요."));
    }

    private void requireActor(Actor actor,boolean lock) {
        if(actor==null) throw new GrowthFailure(401,"AUTHENTICATION_REQUIRED","로그인을 다시 확인해 주세요.");
        User user=user(actor.userId(),lock);
        if(user.authVersion()!=actor.authVersion())
            throw new GrowthFailure(401,"AUTHENTICATION_REQUIRED","로그인을 다시 확인해 주세요.");
        if(!"ACTIVE".equals(user.status()))
            throw new GrowthFailure(403,"GROWTH_ACCOUNT_UNAVAILABLE","성장 기록을 이용할 수 없는 계정입니다.");
        if(policies!=null) policies.requireEligibleUser(actor.userId());
    }

    private GrowthLedger.Policy ledgerPolicy(boolean provisional) {
        GrowthLedger.Policy frozen=GrowthLedger.policy(jdbc,POLICY_VERSION);
        if(frozen!=null || !provisional) return frozen;
        return new GrowthLedger.Policy(new GrowthLedger.Rules(POLICY_VERSION,REVIEW_XP,CHECKIN_XP,DISTRICT_XP,
                BRONZE_XP,SILVER_XP,GOLD_XP,BRONZE_FACILITIES,SILVER_FACILITIES,GOLD_FACILITIES,50,100),
                GrowthLedger.currentTargets(jdbc));
    }

    private ReviewCounts reviewCounts(long userId) {
        List<String> reasons=jdbc.query("""
                SELECT CASE
                    WHEN NOT EXISTS (SELECT 1 FROM toilet_review_submission s
                                     WHERE s.review_id=r.review_id AND s.user_id=r.author_user_id)
                        THEN 'SUBMISSION_UNVERIFIED'
                    WHEN x.review_id IS NOT NULL THEN 'REVIEW_EXCLUDED'
                    WHEN r.satisfaction IS NULL OR r.satisfaction NOT BETWEEN 1 AND 5
                      OR r.cleanliness IS NULL OR r.cleanliness NOT BETWEEN 1 AND 5
                      OR r.paper_available IS NULL THEN 'INVALID_CONTENT'
                    WHEN t.toilet_id IS NULL OR t.visibility_status IS NULL OR t.visibility_status<>'VISIBLE'
                        THEN 'FACILITY_HIDDEN'
                    ELSE 'ELIGIBLE'
                END
                FROM toilet_review r
                LEFT JOIN toilet t ON t.toilet_id=r.toilet_id
                LEFT JOIN growth_review_exclusion x ON x.review_id=r.review_id
                WHERE r.author_user_id=? AND r.author_detached=FALSE
                """,(rs,i)->rs.getString(1),userId);
        Map<String,Integer> excluded=new LinkedHashMap<>();
        for(String reason:List.of("SUBMISSION_UNVERIFIED","REVIEW_EXCLUDED","INVALID_CONTENT","FACILITY_HIDDEN"))
            excluded.put(reason,0);
        int eligible=0;
        for(String reason:reasons) {
            if("ELIGIBLE".equals(reason)) eligible++;
            else excluded.merge(reason,1,Integer::sum);
        }
        return new ReviewCounts(reasons.size(),eligible,java.util.Collections.unmodifiableMap(excluded));
    }

    @Transactional(readOnly=true)
    public Preview previewUser(long userId) {
        User account=user(userId,false);
        if(!"ACTIVE".equals(account.status()))
            throw new GrowthFailure(403,"GROWTH_ACCOUNT_UNAVAILABLE","활성 회원만 소급 지급을 확인할 수 있습니다.");
        GrowthLedger.Policy policy=ledgerPolicy(true);
        GrowthLedger.Forecast forecast=GrowthLedger.forecast(jdbc,userId,policy);
        GrowthLedger.Projection projected=forecast.projection();
        ReviewCounts counts=reviewCounts(userId);
        long expected=forecast.expectedXp();
        long current=GrowthLedger.totalXp(jdbc,userId);
        return new Preview(userId,policy.rules().version(),counts.eligible(),
                projected.distinctFacilities(),projected.pendingRegionFacilities(),expected,current,
                expected-current,forecast.expectedAwards(),counts.linked(),counts.excludedReasons());
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public BackfillResult backfillUser(long userId,boolean apply) {
        if(!apply) {
            Preview preview=previewUser(userId);
            return new BackfillResult(false,preview,
                    new ReconcileResult(userId,false,0,0,preview.currentXp()));
        }
        if(!"ACTIVE".equals(user(userId,true).status()))
            throw new GrowthFailure(403,"GROWTH_ACCOUNT_UNAVAILABLE","활성 회원만 지급할 수 있습니다.");
        if(ledgerPolicy(false)==null) throw new GrowthFailure(409,"GROWTH_POLICY_NOT_INITIALIZED","수집 대상 기준을 먼저 확정해 주세요.");
        Preview preview=previewUser(userId);
        ReconcileResult result=applyUser(userId,"BACKFILL");
        return new BackfillResult(true,preview,result);
    }

    /** Called by the review write transaction after the review row has been saved. */
    @Transactional
    public ReconcileResult reconcileUser(long userId,String reason) {
        if(!enabled) return new ReconcileResult(userId,false,0,0,0);
        if(ledgerPolicy(false)==null) return new ReconcileResult(userId,false,0,0,GrowthLedger.totalXp(jdbc,userId));
        return applyUser(userId,reason);
    }

    /** Privacy cleanup is mandatory even after growth.enabled has been turned off. */
    @Transactional
    public ReconcileResult reconcileAfterDetach(long userId,String reviewKey) {
        if(reviewKey==null || !reviewKey.matches("[0-9a-fA-F-]{36}"))
            throw new IllegalArgumentException("리뷰 식별자가 올바르지 않습니다.");
        Integer linked=jdbc.query("SELECT COUNT(*) FROM toilet_review WHERE review_key=? AND author_user_id=? AND author_detached=FALSE",
                (rs,i)->rs.getInt(1),reviewKey,userId).getFirst();
        if(linked!=0) throw new IllegalStateException("리뷰 연결 해제 후 성장 기록을 정리해야 합니다.");
        if(ledgerPolicy(false)==null) return new ReconcileResult(userId,false,0,0,GrowthLedger.totalXp(jdbc,userId));
        return revokeUser(userId,"REVIEW_UNLINK");
    }

    private ReconcileResult applyUser(long userId,String reason) {
        User account=user(userId,true);
        if(!"ACTIVE".equals(account.status()))
            throw new GrowthFailure(403,"GROWTH_ACCOUNT_UNAVAILABLE","활성 회원만 지급할 수 있습니다.");
        GrowthLedger.Policy policy=ledgerPolicy(false);
        if(policy==null) throw new GrowthFailure(409,"GROWTH_POLICY_NOT_INITIALIZED","수집 대상 기준을 먼저 확정해 주세요.");
        GrowthLedger.Result result=GrowthLedger.reconcile(jdbc,userId,policy,reason,clock);
        return new ReconcileResult(result.userId(),true,result.earnedCount(),result.revokedCount(),result.totalXp());
    }

    private ReconcileResult revokeUser(long userId,String reason) {
        user(userId,true);
        GrowthLedger.Policy policy=ledgerPolicy(false);
        if(policy==null) return new ReconcileResult(userId,false,0,0,GrowthLedger.totalXp(jdbc,userId));
        GrowthLedger.Result result=GrowthLedger.reconcile(jdbc,userId,policy,reason,clock,false);
        return new ReconcileResult(result.userId(),true,result.earnedCount(),result.revokedCount(),result.totalXp());
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ExclusionResult setReviewExcluded(long reviewId,boolean excluded,String reason) {
        if(reviewId<=0) throw new IllegalArgumentException("리뷰 ID를 확인해 주세요.");
        if(reason==null || reason.isBlank() || reason.length()>200)
            throw new IllegalArgumentException("제외 사유를 200자 이내로 입력해 주세요.");
        var row=jdbc.query("SELECT review_key,author_user_id FROM toilet_review WHERE review_id=?",
                (rs,i)->new ReviewOwner(rs.getString(1),rs.getObject(2,Long.class)),reviewId).stream().findFirst()
                .orElseThrow(()->new GrowthFailure(404,"REVIEW_NOT_FOUND","리뷰를 찾을 수 없습니다."));
        Long userId=row.userId();
        if(userId==null) userId=jdbc.query("SELECT user_id FROM growth_review_evidence WHERE review_key=?",
                (rs,i)->rs.getLong(1),row.key()).stream().findFirst().orElse(null);
        if(userId!=null) user(userId,true);
        int existing=jdbc.queryForObject("SELECT COUNT(*) FROM growth_review_exclusion WHERE review_id=?",Integer.class,reviewId);
        if(excluded) {
            if(existing==0) jdbc.update("INSERT INTO growth_review_exclusion(review_id,review_key,reason,excluded_at) VALUES(?,?,?,?)",
                    reviewId,row.key(),reason.strip(),now());
            else jdbc.update("UPDATE growth_review_exclusion SET reason=?,excluded_at=? WHERE review_id=?",reason,now(),reviewId);
        } else if(existing!=0) jdbc.update("DELETE FROM growth_review_exclusion WHERE review_id=?",reviewId);
        ReconcileResult result=userId==null || ledgerPolicy(false)==null
                ?new ReconcileResult(userId==null?0:userId,false,0,0,userId==null?0:GrowthLedger.totalXp(jdbc,userId))
                :excluded?revokeUser(userId,"REVIEW_EXCLUDED"):applyUser(userId,"REVIEW_RESTORED");
        return new ExclusionResult(reviewId,excluded,result);
    }

    @Transactional(readOnly=true)
    public Summary summary(Actor actor) {
        requireActor(actor,false);
        if(!enabled) return blankSummary();
        GrowthLedger.Policy policy=ledgerPolicy(false);
        if(policy==null) return blankSummary();
        return summaryLoaded(actor.userId(),policy);
    }

    @Transactional
    public Summary checkIn(Actor actor) {
        requireActor(actor,true);
        if(!enabled) throw new GrowthFailure(503,"GROWTH_DISABLED","성장 기록을 준비하고 있어요.");
        GrowthLedger.Policy policy=ledgerPolicy(false);
        if(policy==null) throw new GrowthFailure(503,"GROWTH_DISABLED","성장 기록을 준비하고 있어요.");
        String date=today().toString();
        boolean already=GrowthLedger.awards(jdbc,actor.userId()).stream()
                .anyMatch(a->a.active() && "CHECKIN".equals(a.kind()) && date.equals(a.key()));
        if(!already) {
            GrowthLedger.insertAward(jdbc,actor.userId(),
                    new GrowthLedger.AwardSpec("CHECKIN",date,policy.rules().version(),policy.rules().checkinXp()),
                    now(),"DAILY_CHECKIN");
            GrowthLedger.refreshBalance(jdbc,actor.userId(),now());
        }
        return summaryLoaded(actor.userId(),policy);
    }

    @Transactional(readOnly=true)
    public History history(Actor actor) {
        requireActor(actor,false);
        if(!enabled) return new History(List.of());
        if(ledgerPolicy(false)==null) return new History(List.of());
        return new History(jdbc.query("""
                SELECT event_id,delta_xp,event_kind,reason,happened_at
                FROM growth_xp_event WHERE user_id=? ORDER BY event_id DESC LIMIT 50
                """,(rs,i)->new HistoryItem(rs.getLong(1),rs.getInt(2),rs.getString(3),rs.getString(4),
                rs.getObject(5,LocalDateTime.class)),actor.userId()));
    }

    private Summary blankSummary() {
        return new Summary(false,POLICY_VERSION,0,1,"white","흰색 휴지",2,30,30,0,false,List.of(),List.of());
    }

    private Summary summaryLoaded(long userId,GrowthLedger.Policy policy) {
        long xp=GrowthLedger.totalXp(jdbc,userId);
        int level=level(xp); long currentThreshold=threshold(level),nextThreshold=threshold(level+1);
        String[] ranks={"white","green","yellow","blue","red","pink","black"};
        String[] names={"흰색 휴지","초록 휴지","노랑 휴지","파랑 휴지","빨강 휴지","핑크 휴지","검정 휴지"};
        int rankIndex=level<=2?0:level<=4?1:level<=9?2:level<=14?3:level<=24?4:level<=39?5:6;
        List<GrowthLedger.Award> awards=GrowthLedger.awards(jdbc,userId).stream().filter(GrowthLedger.Award::active).toList();
        Map<String,GrowthLedger.Target> byDistrict=policy.targets().stream()
                .collect(Collectors.toMap(GrowthLedger.Target::code,Function.identity()));
        Map<String,GrowthLedger.Policy> awardPolicies=new HashMap<>();
        awardPolicies.put(policy.rules().version(),policy);
        Map<String,String> provinceNames=new HashMap<>();
        Map<String,Integer> targetCounts=new HashMap<>();
        for(GrowthLedger.Target target:policy.targets()) {
            provinceNames.putIfAbsent(target.sidoCode(),target.sidoName());
            targetCounts.merge(target.sidoCode(),1,Integer::sum);
        }
        List<Badge> badges=new ArrayList<>();
        Map<String,Integer> earnedDistrictCounts=new HashMap<>();
        Map<String,String> tiers=new HashMap<>();
        for(GrowthLedger.Award award:awards) {
            if(award.key()==null) continue;
            if("DISTRICT".equals(award.kind()) && award.key().startsWith("D:")) {
                String code=award.key().substring(2);
                GrowthLedger.Target target=byDistrict.get(code);
                if(target==null) {
                    GrowthLedger.Policy original=awardPolicies.computeIfAbsent(award.version(),
                            version->GrowthLedger.policy(jdbc,version));
                    if(original!=null) target=original.targets().stream()
                            .filter(item->item.code().equals(code)).findFirst().orElse(null);
                }
                if(target!=null) {
                    badges.add(new Badge("district",code,target.displayName(),null,award.amount(),award.awardedAt()));
                    if(targetCounts.containsKey(target.sidoCode())) earnedDistrictCounts.merge(target.sidoCode(),1,Integer::sum);
                }
            } else if(award.kind().startsWith("MEDAL_") && award.key().startsWith("P:")) {
                String code=award.key().substring(2),tier=award.kind().substring(6).toLowerCase();
                String name=provinceNames.get(code);
                if(name==null) {
                    GrowthLedger.Policy original=awardPolicies.computeIfAbsent(award.version(),
                            version->GrowthLedger.policy(jdbc,version));
                    if(original!=null) name=original.targets().stream().filter(item->item.sidoCode().equals(code))
                            .map(GrowthLedger.Target::sidoName).findFirst().orElse(null);
                }
                badges.add(new Badge("regional_medal",code,name==null?code:name,tier,
                        award.amount(),award.awardedAt()));
                String old=tiers.get(code);
                if(old==null || tierOrder(tier)>tierOrder(old)) tiers.put(code,tier);
            }
        }
        badges.sort(Comparator.comparing(Badge::type).thenComparing(Badge::code)
                .thenComparingInt(b->b.tier()==null?0:tierOrder(b.tier())));
        GrowthLedger.Projection projection=GrowthLedger.project(jdbc,userId,policy);
        List<Region> regions=new ArrayList<>();
        for(String code:targetCounts.keySet()) {
            int target=targetCounts.get(code);
            regions.add(new Region(code,provinceNames.get(code),target,earnedDistrictCounts.getOrDefault(code,0),
                    projection.provinceFacilities().getOrDefault(code,0),tiers.get(code),
                    policy.rules().bronzeFacilities(),policy.rules().silverFacilities(),policy.rules().goldFacilities(),
                    GrowthLedger.ceilPercent(target,policy.rules().silverPercent()),
                    GrowthLedger.ceilPercent(target,policy.rules().goldPercent())));
        }
        regions.sort(Comparator.comparing(Region::code));
        String date=today().toString();
        boolean checkInAvailable=awards.stream().noneMatch(a->"CHECKIN".equals(a.kind()) && date.equals(a.key()));
        int progress=(int)Math.min(100,Math.max(0,((xp-currentThreshold)*100)/(nextThreshold-currentThreshold)));
        return new Summary(true,policy.rules().version(),xp,level,ranks[rankIndex],names[rankIndex],level+1,
                nextThreshold,nextThreshold-xp,progress,checkInAvailable,List.copyOf(badges),List.copyOf(regions));
    }

    private static int tierOrder(String value) {
        return switch(value) {case "gold"->3;case "silver"->2;default->1;};
    }
    public static long threshold(int level) { return 10L*(level*(long)level-1); }
    public static int level(long xp) {
        int level=(int)Math.sqrt(xp/10.0+1);
        while(threshold(level+1)<=xp)level++;
        while(level>1 && threshold(level)>xp)level--;
        return Math.max(1,level);
    }
}
