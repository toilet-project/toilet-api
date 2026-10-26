package com.example.toiletapi.growth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** POST keeps private search terms out of URLs; both operations only SELECT current balances. */
@RestController
@RequestMapping("/api/admin/v1/growth/ranking")
public class AdminGrowthRankingController {
    private final AdminGrowthAccess access;private final AdminGrowthRankingService service;
    public AdminGrowthRankingController(AdminGrowthAccess access,AdminGrowthRankingService service) {this.access=access;this.service=service;}
    @PostMapping(value="/search",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminGrowthRankingModels.Page> search(@AuthenticationPrincipal Jwt jwt,
            @RequestBody byte[] body,HttpServletRequest request) {
        String authorization=request.getHeader("Authorization");
        boolean bearer=request.getHeader("Origin")==null && jwt!=null && authorization!=null
                && authorization.equals("Bearer "+jwt.getTokenValue());
        if(!bearer && !"https://admin.geupddong.com".equals(request.getHeader("Origin")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"허용되지 않은 요청 출처입니다.");
        access.requireAdmin(jwt);return response(service.search(AdminGrowthRankingModels.parse(body)));
    }
    @GetMapping("/distribution")
    public ResponseEntity<AdminGrowthRankingModels.Distribution> distribution(@AuthenticationPrincipal Jwt jwt) {
        access.requireAdmin(jwt);return response(service.distribution());
    }
    private static <T> ResponseEntity<T> response(T body) {return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(body);}
}
