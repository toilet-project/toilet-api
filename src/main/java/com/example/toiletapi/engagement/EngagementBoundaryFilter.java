package com.example.toiletapi.engagement;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(Ordered.HIGHEST_PRECEDENCE+21)
public class EngagementBoundaryFilter extends OncePerRequestFilter {
    private final EngagementConfiguration.Settings settings;
    public EngagementBoundaryFilter(@org.springframework.beans.factory.annotation.Value("${engagement.allowed-origins:https://geupddong.com,https://www.geupddong.com}") String origins) {
        this.settings=new EngagementConfiguration.Settings(false,"",java.util.Arrays.stream(origins.split(",")).map(String::strip).collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI();
        return !path.matches("/api/v1/toilets/[0-9]+/(?:engagement|views)")
                && !path.matches("/api/v1/engagement/toilets/[0-9]+/like")
                && !path.equals("/api/v1/engagement/likes");
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        res.setHeader("Cache-Control","private, no-store");res.setHeader("Vary","Cookie, Authorization, Origin");
        if(!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())) {
            // Public view events require a browser origin; authenticated likes may also use a verified native bearer.
            boolean nativeCandidate=req.getRequestURI().endsWith("/like") && req.getHeader("Origin")==null
                    && req.getHeader("Authorization")!=null && req.getHeader("Authorization").startsWith("Bearer ");
            try { if(!nativeCandidate)settings.requireOrigin(req.getHeader("Origin")); }
            catch(EngagementFailure failure) { res.setStatus(403);res.setContentType("application/json");res.getWriter().write("{\"error\":{\"code\":\"ORIGIN_DENIED\"}}");return; }
            if(req.getContentLengthLong()>1024) { res.sendError(413);return; }
        }
        chain.doFilter(req,res);
    }
}
