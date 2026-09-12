package com.example.toiletapi.review;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Review-only boundary. Cookie-authenticated mutations cannot be invoked by sibling origins. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ReviewBoundaryFilter extends OncePerRequestFilter {
    private static final Set<String> ORIGINS=Set.of("https://geupddong.com","https://www.geupddong.com","https://preview.geupddong.com");
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI();
        return !path.equals("/api/v1/reviews") && !path.startsWith("/api/v1/reviews/")
                && !path.matches("/api/v1/toilets/[0-9]+/reviews(?:/summary)?");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        // Including public names: avoid serving stale identities after withdrawal/unlink.
        response.setHeader("Cache-Control","private, no-store");
        response.setHeader("Vary","Cookie, Authorization, Origin");
        if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod()) && !ORIGINS.contains(String.valueOf(request.getHeader("Origin")))) {
            error(response,403,"REVIEW_ORIGIN_DENIED","허용되지 않은 요청 출처입니다.");return;
        }
        if(request.getContentLengthLong()>8192) {error(response,413,"REVIEW_REQUEST_TOO_LARGE","리뷰 요청이 너무 큽니다.");return;}
        chain.doFilter(request,response);
    }
    private static void error(HttpServletResponse response,int status,String code,String message) throws IOException {
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":{\"code\":\""+code+"\",\"message\":\""+message+"\"}}");
    }
}
