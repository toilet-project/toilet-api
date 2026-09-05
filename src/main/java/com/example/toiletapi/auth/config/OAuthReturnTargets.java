package com.example.toiletapi.auth.config;

import jakarta.servlet.http.HttpServletRequest;

/** 서버가 소유한 복귀 주소만 사용한다. 사용자 입력 URL을 redirect에 사용하지 않는다. */
public final class OAuthReturnTargets {
    public static final String SESSION_ATTRIBUTE = "oauth.login.return-url";
    public static final String ADMIN = "https://admin.geupddong.com";
    public static final String PREVIEW = "https://preview.geupddong.com";
    private OAuthReturnTargets() { }

    public static String consume(HttpServletRequest request, String home) {
        var session = request.getSession(false);
        Object target = session == null ? null : session.getAttribute(SESSION_ATTRIBUTE);
        if (session != null) session.removeAttribute(SESSION_ATTRIBUTE);
        return ADMIN.equals(target) ? ADMIN : PREVIEW.equals(target) ? PREVIEW : home;
    }
}
