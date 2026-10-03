package com.example.toiletapi.report;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded, fail-closed write boundary. Never trust forwarding headers supplied by clients. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE + 22)
public class QuickReportBoundaryFilter extends OncePerRequestFilter {
    private static final Set<String> ORIGINS = Set.of("https://geupddong.com", "https://www.geupddong.com", "https://preview.geupddong.com");
    private final boolean enabled;
    private final String salt = UUID.randomUUID().toString();
    private final Map<String, Bucket> buckets = new HashMap<>();
    private record Bucket(long minute, int count) { }
    public QuickReportBoundaryFilter(@Value("${reports.quick-enabled:false}") boolean enabled) { this.enabled = enabled; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/reports") && !request.getRequestURI().startsWith("/api/admin/v1/reports");
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        res.setHeader("Cache-Control", "private, no-store");
        res.setHeader("Vary", "Cookie, Authorization, Origin");
        boolean quick = Set.of("/api/v1/reports/quick", "/api/v1/reports/guest").contains(req.getRequestURI());
        if (!quick || "OPTIONS".equals(req.getMethod())) { chain.doFilter(req, res); return; }
        if (!enabled) { fail(res, 404, "REPORTS_DISABLED"); return; }
        if (!"POST".equals(req.getMethod())) { fail(res, 405, "METHOD_NOT_ALLOWED"); return; }
        boolean nativeMember = req.getRequestURI().endsWith("/quick") && req.getHeader("Origin") == null
                && req.getHeader("Authorization") != null && req.getHeader("Authorization").startsWith("Bearer ");
        if (!nativeMember && !ORIGINS.contains(Objects.toString(req.getHeader("Origin"), ""))) { fail(res, 403, "ORIGIN_DENIED"); return; }
        if (req.getContentType() == null || !req.getContentType().toLowerCase(Locale.ROOT).matches("application/json(?:;.*)?")) { fail(res, 415, "JSON_REQUIRED"); return; }
        if (req.getContentLengthLong() > 8192) { fail(res, 413, "PAYLOAD_TOO_LARGE"); return; }
        boolean guest = req.getRequestURI().endsWith("/guest");
        if (!allow("peer:" + req.getRemoteAddr(), 120) || (guest && !allow("guest:" + Objects.toString(req.getHeader("X-Report-Guest"), req.getRemoteAddr()), 12))) {
            res.setHeader("Retry-After", "60"); fail(res, 429, "RATE_LIMITED"); return;
        }
        byte[] body = req.getInputStream().readNBytes(8193);
        if (body.length > 8192) { fail(res, 413, "PAYLOAD_TOO_LARGE"); return; }
        chain.doFilter(new HttpServletRequestWrapper(req) {
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        }, res);
    }
    private synchronized boolean allow(String input, int maximum) {
        long minute = System.currentTimeMillis() / 60000;
        if (buckets.size() >= 10000) buckets.entrySet().removeIf(entry -> entry.getValue().minute() < minute);
        String key;
        try { key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((salt + input).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { return false; }
        Bucket previous = buckets.get(key);
        if (previous == null && buckets.size() >= 10000) return false;
        int count = previous == null || previous.minute() != minute ? 1 : previous.count() + 1;
        buckets.put(key, new Bucket(minute, count));
        return count <= maximum;
    }
    private static void fail(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.getWriter().write("{\"error\":{\"code\":\"" + code + "\"}}");
    }
}
