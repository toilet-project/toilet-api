package com.example.toiletapi.growth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Prevent browser and shared caches from retaining member XP and badge state, including errors. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
public class GrowthBoundaryFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI();
        return !path.equals("/api/v1/growth") && !path.startsWith("/api/v1/growth/")
                && !path.equals("/api/admin/v1/growth") && !path.startsWith("/api/admin/v1/growth/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,
            FilterChain chain) throws ServletException,IOException {
        response.setHeader("Cache-Control","private, no-store");
        response.setHeader("Vary","Cookie, Authorization, Origin");
        chain.doFilter(request,response);
    }
}
