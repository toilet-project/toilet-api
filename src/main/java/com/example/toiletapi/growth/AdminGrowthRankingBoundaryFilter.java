package com.example.toiletapi.growth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Private search bodies are never logged, cached, echoed or accepted in a URL. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+21)
public class AdminGrowthRankingBoundaryFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI();return !path.equals("/api/admin/v1/growth/ranking") && !path.startsWith("/api/admin/v1/growth/ranking/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("Cache-Control","private, no-store");response.setHeader("Vary","Cookie, Authorization, Origin");
        if(request.getQueryString()!=null) {deny(response,400,"XP_RANKING_INVALID_REQUEST");return;}
        String authorization=request.getHeader("Authorization");
        boolean nativeCandidate=request.getHeader("Origin")==null && authorization!=null && authorization.startsWith("Bearer ") && authorization.length()>7;
        if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod()) && !nativeCandidate
                && !"https://admin.geupddong.com".equals(request.getHeader("Origin"))) {deny(response,403,"XP_RANKING_ORIGIN_DENIED");return;}
        if(request.getContentLengthLong()>4096) {deny(response,413,"XP_RANKING_REQUEST_TOO_LARGE");return;}
        byte[] body=request.getInputStream().readNBytes(4097);
        if(body.length>4096) {deny(response,413,"XP_RANKING_REQUEST_TOO_LARGE");return;}
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input=new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() {return input.read();}
                    @Override public int read(byte[] bytes,int offset,int length) {return input.read(bytes,offset,length);}
                    @Override public boolean isFinished() {return input.available()==0;}
                    @Override public boolean isReady() {return true;}
                    @Override public void setReadListener(ReadListener listener) {throw new IllegalStateException("Synchronous private request required");}
                };
            }
            @Override public BufferedReader getReader() {return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        },response);
    }
    private static void deny(HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":{\"code\":\""+code+"\",\"message\":\"경험치 조회 요청을 확인해 주세요.\"}}");
    }
}
