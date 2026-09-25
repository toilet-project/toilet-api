package com.example.toiletapi.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The callback is fixed by the service; callers never supply a redirect URL. */
public final class MobileOAuthSession {
    public static final String ATTRIBUTE = "oauth.mobile.attempt";
    public static final String CALLBACK = "geupddong://auth/callback";
    private MobileOAuthSession() { }

    public static void begin(HttpServletRequest request, String state, String challenge) {
        if (!valid(state) || !valid(challenge)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var session = request.getSession(true);
        session.removeAttribute(OAuthReturnTargets.SESSION_ATTRIBUTE);
        session.setAttribute(ATTRIBUTE, new Attempt(state, challenge, Instant.now().getEpochSecond()));
    }

    public static Attempt consume(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        Object attempt = session.getAttribute(ATTRIBUTE);
        session.removeAttribute(ATTRIBUTE);
        return attempt instanceof Attempt value ? value : null;
    }

    private static boolean valid(String value) { return value != null && value.matches("[A-Za-z0-9_-]{43}"); }
    public record Attempt(String state, String challenge, long createdAt) implements Serializable {
        public boolean expired() { return Instant.now().getEpochSecond() - createdAt > 600; }
        public String errorUrl(String error) { return CALLBACK + "?state=" + state + "&error=" + error; }
    }
}
