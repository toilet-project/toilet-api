package com.example.toiletapi.engagement;

import com.example.toiletapi.auth.controller.AuthenticatedMutationBoundary;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import static com.example.toiletapi.engagement.EngagementService.*;

@RestController
public class EngagementController {
    private final EngagementService service;
    private final EngagementConfiguration.Settings settings;
    private final Clock clock;
    private final Map<String,Integer> limits=new HashMap<>();
    private long minute=-1;
    public EngagementController(EngagementService service,EngagementConfiguration.Settings settings,@Qualifier("engagementClock") Clock clock) { this.service=service;this.settings=settings;this.clock=clock; }
    @GetMapping("/api/v1/toilets/{id}/engagement")
    public Counts counts(@PathVariable long id) { return service.counts(id); }
    @PostMapping("/api/v1/toilets/{id}/views")
    public ViewResult view(@PathVariable long id,@RequestBody ViewRequest body,HttpServletRequest req) {
        settings.requireEnabled();settings.requireOrigin(req.getHeader("Origin"));
        if(body==null || body.sessionId()==null || body.sessionId().length()>64 || body.eventId()==null || body.eventId().length()>64)
            throw new EngagementFailure(400,"INVALID_EVENT","조회 요청을 확인해 주세요.");
        rateLimit(service.hash(body.sessionId()));
        String ua=String.valueOf(req.getHeader("User-Agent")).toLowerCase(Locale.ROOT);
        boolean bot=ua.equals("null") || ua.isBlank() || ua.matches(".*(?:bot|spider|crawler|headless|preview|slurp|yeti|curl|wget).*?");
        return service.view(id,body,bot);
    }
    @GetMapping("/api/v1/engagement/toilets/{id}/like")
    public LikeState mine(@PathVariable long id,@AuthenticationPrincipal Jwt jwt) { return service.mine(id,actor(jwt)); }
    @PutMapping("/api/v1/engagement/toilets/{id}/like")
    public LikeState like(@PathVariable long id,@AuthenticationPrincipal Jwt jwt,HttpServletRequest req) {
        AuthenticatedMutationBoundary.requireTrustedOriginOrBearer(req,jwt);return service.setLike(id,actor(jwt),true);
    }
    @DeleteMapping("/api/v1/engagement/toilets/{id}/like")
    public LikeState unlike(@PathVariable long id,@AuthenticationPrincipal Jwt jwt,HttpServletRequest req) {
        AuthenticatedMutationBoundary.requireTrustedOriginOrBearer(req,jwt);return service.setLike(id,actor(jwt),false);
    }
    private synchronized void rateLimit(String session) {
        long current=clock.instant().getEpochSecond()/60;
        if(current!=minute) { limits.clear();minute=current; }
        if(limits.size()>=10000 && !limits.containsKey(session) || limits.merge(session,1,Integer::sum)>60)
            throw new EngagementFailure(429,"TOO_MANY_REQUESTS","잠시 후 다시 시도해 주세요.");
    }
    private static Actor actor(Jwt jwt) {
        try { long id=Long.parseLong(jwt.getSubject());Number version=jwt.getClaim("auth_version");if(id<=0)throw new IllegalArgumentException();return new Actor(id,version==null?0:version.longValue()); }
        catch(RuntimeException e) {throw new EngagementFailure(401,"AUTHENTICATION_REQUIRED","로그인 후 좋아요를 눌러 주세요.");}
    }
}
