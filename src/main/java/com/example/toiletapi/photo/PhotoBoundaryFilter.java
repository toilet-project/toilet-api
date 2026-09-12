package com.example.toiletapi.photo;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(Ordered.HIGHEST_PRECEDENCE+19)
public class PhotoBoundaryFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI();
        return !path.startsWith("/api/v1/auth/me/photo") && !path.matches("/api/v1/toilets/[^/]+/reviews/[^/]+/photo");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("Cache-Control","private, no-store");response.setHeader("CDN-Cache-Control","no-store");
        response.setHeader("Cloudflare-CDN-Cache-Control","no-store");response.setHeader("Vary","Cookie, Authorization, Origin");
        response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("X-Robots-Tag","noindex, noimageindex");
        if(request.getContentLengthLong()>1024) {response.setStatus(413);return;}
        chain.doFilter(request,response);
    }
}
