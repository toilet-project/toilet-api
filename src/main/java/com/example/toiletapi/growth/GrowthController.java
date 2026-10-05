package com.example.toiletapi.growth;

import com.example.toiletapi.auth.controller.AuthenticatedMutationBoundary;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Private member growth data. Authorization and current account state are checked by GrowthService. */
@RestController
@RequestMapping("/api/v1/growth")
public class GrowthController {
    private final GrowthService growth;

    public GrowthController(GrowthService growth) { this.growth = growth; }

    @GetMapping("/me")
    public GrowthService.Summary me(@AuthenticationPrincipal Jwt jwt) {
        return growth.summary(actor(jwt));
    }

    @GetMapping("/history")
    public GrowthService.HistoryResponse history(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) String direction,
            @RequestParam(required=false) Integer page,
            @RequestParam(required=false) Integer size) {
        GrowthService.Actor actor=actor(jwt);
        if(direction==null && page==null && size==null) return growth.history(actor);
        return growth.historyPage(actor,direction,page==null?0:page,size==null?10:size);
    }

    @PostMapping("/check-in")
    public GrowthService.Summary checkIn(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        AuthenticatedMutationBoundary.requireTrustedOriginOrBearer(request,jwt);
        return growth.checkIn(actor(jwt));
    }

    private static GrowthService.Actor actor(Jwt jwt) {
        try {
            long id=Long.parseLong(jwt.getSubject());
            Number version=jwt.getClaim("auth_version");
            if(id<=0) throw new IllegalArgumentException();
            return new GrowthService.Actor(id,version==null?0:version.longValue());
        } catch(RuntimeException error) {
            throw new GrowthFailure(401,"AUTHENTICATION_REQUIRED","로그인을 다시 확인해 주세요.");
        }
    }
}
