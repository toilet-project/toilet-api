package com.example.toiletapi.review;

import com.example.toiletapi.policy.service.PolicyConsentService;
import com.example.toiletapi.review.ReviewConfiguration.ReviewSettings;
import com.example.toiletapi.review.ReviewModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReviewService {
    public record Actor(long id, long authVersion) { }
    private final ReviewRepository repository;
    private final PolicyConsentService policies;
    private final ReviewSettings settings;
    private final Clock clock;
    private final ReviewUnlinkProtection unlinkProtection;
    private static final ZoneOffset KST=ZoneOffset.ofHours(9);
    private static final ReviewRules.LocationPolicy LOCATION = new ReviewRules.LocationPolicy(150,50);
    public ReviewService(ReviewRepository repository, PolicyConsentService policies, ReviewSettings settings,
                         @Qualifier("reviewClock") Clock clock,ReviewUnlinkProtection unlinkProtection) {
        this.repository=repository;this.policies=policies;this.settings=settings;this.clock=clock;this.unlinkProtection=unlinkProtection;
    }
    private void authorize(Actor actor) {
        settings.requireEnabled();
        // Same row lock as withdrawal/erasure; serializes cross-instance writes and guards in-flight revocation.
        var user=repository.lockAuthor(actor.id()).orElseThrow(ReviewService::unauthorized);
        if(user.authVersion()!=actor.authVersion())throw unauthorized();
        if(!"ACTIVE".equals(user.status()))throw new ReviewFailure(403,"REVIEW_ACCOUNT_UNAVAILABLE","리뷰를 이용할 수 없는 계정입니다.");
        policies.requireEligibleUser(actor.id());
    }
    public Item create(Actor actor, Create request, String requestKey) {
        authorize(actor);
        if(request==null || request.toiletId()==null || request.toiletId()<=0)throw new IllegalArgumentException("대상 화장실을 선택해 주세요.");
        var content=ReviewRules.validate(request.content());
        String key=key(requestKey),hash=hash(request.toiletId(),content);
        var prior=repository.submission(actor.id(),key);
        if(prior.isPresent()) {
            if(!prior.get().hash().equals(hash))throw new ReviewFailure(409,"REVIEW_REQUEST_REUSED","다른 내용에 같은 등록 요청이 사용됐어요.");
            var row=repository.find(prior.get().reviewId()).orElseThrow(ReviewService::notFound);
            if(!Objects.equals(row.authorId(),actor.id()) || row.detached())throw notFound();
            return item(row,actor.id()); // Safe retry of an already accepted write; no new position is recorded.
        }
        var toilet=repository.facility(request.toiletId(),true).orElseThrow(ReviewService::notFound);
        Instant instant=clock.instant();
        LocalDateTime now=local(instant);
        var creationStatus=creationStatus(actor.id(),request.toiletId(),now);
        if(!creationStatus.canCreate())throw new ReviewFailure(409,"REVIEW_ALREADY_EXISTS",
                creationStatus.existingReviewId()!=null?"작성한 리뷰 내역이 있습니다.":"이 화장실은 작성 후 24시간이 지나야 다시 리뷰를 남길 수 있어요.",creationStatus);
        ReviewRules.requireNearby(request.position(),toilet.latitude(),toilet.longitude(),instant,LOCATION);
        var guard=repository.guard(actor.id());
        int count=guard.filter(g->g.date().equals(now.toLocalDate())).map(ReviewRepository.Guard::count).orElse(0);
        if(guard.isPresent() && now.isBefore(guard.get().lastCreatedAt().plusSeconds(settings.cooldownSeconds())))
            throw new ReviewFailure(429,"REVIEW_COOLDOWN","연속 등록은 잠시 기다린 뒤 다시 시도해 주세요.");
        if(count>=settings.maxPerDay())throw new ReviewFailure(429,"REVIEW_DAILY_LIMIT","오늘 작성 가능한 리뷰 수를 모두 이용했어요.");
        long id=repository.insert(actor.id(),request.toiletId(),content,now);
        repository.remember(actor.id(),key,hash,id);
        repository.recordCreation(actor.id(),now,count+1,guard.isPresent());
        repository.recordToiletCreation(actor.id(),request.toiletId(),now);
        return item(repository.find(id).orElseThrow(),actor.id());
    }
    public CreationStatus creationStatus(Actor actor,long toilet) {
        authorize(actor);
        repository.facility(toilet,false).orElseThrow(ReviewService::notFound);
        return creationStatus(actor.id(),toilet,local(clock.instant()));
    }
    private CreationStatus creationStatus(long user,long toilet,LocalDateTime now) {
        var next=repository.nextAllowedAt(user,toilet).filter(value->now.isBefore(value));
        // Only a still-owned review may become a private navigation target. Never recover an unlinked ID.
        var recent=repository.recentOwned(user,toilet,now.minusHours(24));
        if(recent.isPresent()) {
            var reviewNext=recent.get().createdAt().plusHours(24);
            if(next.isEmpty() || reviewNext.isAfter(next.get()))next=java.util.Optional.of(reviewNext);
        }
        return new CreationStatus(next.isEmpty(),next.isPresent() && recent.isPresent()?Long.toString(recent.get().id()):null,
                next.map(value->value.atOffset(KST)).orElse(null));
    }
    public Item edit(Actor actor,long id,Edit request) {
        authorize(actor);
        if(request==null)throw new IllegalArgumentException("수정 내용을 입력해 주세요.");
        var row=manageable(actor,id,request.version());
        var content=ReviewRules.validate(request.content());
        if(repository.edit(id,actor.id(),row.version(),content,local(clock.instant()))!=1)throw conflict();
        return item(repository.find(id).orElseThrow(),actor.id());
    }
    public Detached detach(Actor actor,long id,Detach request) {
        authorize(actor);
        if(request==null || !Boolean.TRUE.equals(request.acknowledgeContentRetention()))
            throw new IllegalArgumentException("리뷰 내용이 남고 작성자 연결을 복구할 수 없다는 안내를 확인해 주세요.");
        var row=manageable(actor,id,request.version());
        // Authenticate ownership/version/deadline before any durable intent; never repurpose account-erasure records.
        unlinkProtection.record(row.reviewKey());
        if(repository.detach(id,actor.id(),row.version())!=1)throw conflict();
        return new Detached(Long.toString(id),"익명",true);
    }
    public Item mineDetail(Actor actor,long id) {
        authorize(actor);var row=repository.find(id).orElseThrow(ReviewService::notFound);
        if(!Objects.equals(row.authorId(),actor.id()) || row.detached())throw notFound();
        return item(row,actor.id());
    }
    public Page mine(Actor actor,LocalDate from,LocalDate to,String cursor,int size) {
        authorize(actor);validateRange(from,to,size);
        return page(repository.page(actor.id(),null,from,to,ReviewCursor.parse(cursor),size),size,actor.id());
    }
    @Transactional(readOnly=true)
    public Page publicPage(long toilet,String cursor,int size) {
        settings.requireEnabled();validateRange(null,null,size);
        repository.facility(toilet,false).orElseThrow(ReviewService::notFound);
        return page(repository.page(null,toilet,null,null,ReviewCursor.parse(cursor),size),size,null);
    }
    @Transactional(readOnly=true)
    public Summary summary(long toilet) {
        settings.requireEnabled();repository.facility(toilet,false).orElseThrow(ReviewService::notFound);
        return repository.summary(toilet,local(clock.instant()));
    }
    private ReviewRepository.Row manageable(Actor actor,long id,Long version) {
        var row=repository.find(id).orElseThrow(ReviewService::notFound);
        if(!Objects.equals(row.authorId(),actor.id()) || row.detached())throw notFound();
        if(!ReviewRules.canManage(row.authorId(),actor.id(),row.createdAt().toInstant(KST),clock.instant()))
            throw new ReviewFailure(403,"REVIEW_EDIT_EXPIRED","작성 후 7일이 지나 수정·작성자 정보 지우기가 종료됐어요.");
        if(version==null || version<0)throw new IllegalArgumentException("리뷰 버전을 확인해 주세요.");
        if(version!=row.version())throw conflict();
        return row;
    }
    private Item item(ReviewRepository.Row row,Long owner) {
        String name=row.detached()?"익명":row.authorId()==null || "WITHDRAWN".equals(row.authorStatus())?"탈퇴한 사용자"
                :row.displayName()==null || row.displayName().isBlank()?"급똥 사용자":row.displayName();
        return new Item(Long.toString(row.id()),row.toiletId(),row.toiletName(),row.satisfaction(),row.cleanliness(),row.paper(),
                row.waitMinutes(),row.comment(),row.version(),row.createdAt().atOffset(KST),row.updatedAt().atOffset(KST),
                row.createdAt().plusDays(7).atOffset(KST),owner!=null && ReviewRules.canManage(row.authorId(),owner,row.createdAt().toInstant(KST),clock.instant()),
                row.authorId()==null,name);
    }
    private Page page(java.util.List<ReviewRepository.Row> rows,int size,Long owner) {
        boolean more=rows.size()>size;var visible=rows.subList(0,Math.min(size,rows.size()));
        String next=more?new ReviewCursor(visible.getLast().createdAt(),visible.getLast().id()).encode():null;
        return new Page(visible.stream().map(r->item(r,owner)).toList(),next,more);
    }
    private static void validateRange(LocalDate from,LocalDate to,int size) {
        if(size<1 || size>50 || (from!=null && (from.getYear()<1000 || from.getYear()>9998))
                || (to!=null && (to.getYear()<1000 || to.getYear()>9998)) || (from!=null && to!=null && from.isAfter(to)))
            throw new IllegalArgumentException("날짜 범위와 목록 개수(1~50)를 확인해 주세요.");
    }
    private static String key(String value) {
        try { if(value==null || !UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();return value; }
        catch(RuntimeException e) {throw new IllegalArgumentException("등록 요청 식별자가 올바르지 않아요.");}
    }
    private static String hash(long toilet,ReviewRules.Content c) {
        try {
            String value=toilet+":"+c.satisfaction()+":"+c.cleanliness()+":"+c.paperAvailable()+":"+c.waitMinutes()+":"+c.comment();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException("SHA-256 unavailable");}
    }
    private static LocalDateTime local(Instant value) {return LocalDateTime.ofInstant(value.truncatedTo(ChronoUnit.MICROS),KST);}
    private static ReviewFailure notFound() {return new ReviewFailure(404,"REVIEW_NOT_FOUND","리뷰 또는 화장실을 찾을 수 없어요.");}
    private static ReviewFailure conflict() {return new ReviewFailure(409,"REVIEW_CHANGED","리뷰가 변경됐어요. 다시 불러온 뒤 수정해 주세요.");}
    private static ReviewFailure unauthorized() {return new ReviewFailure(401,"AUTHENTICATION_REQUIRED","로그인을 다시 확인해 주세요.");}
}
