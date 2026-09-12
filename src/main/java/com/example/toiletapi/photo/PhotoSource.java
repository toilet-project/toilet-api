package com.example.toiletapi.photo;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/** Provider URL exists only in a bounded in-memory job. Never log this record. */
public record PhotoSource(URI uri) {
    public static PhotoSource from(String provider, Map<String,Object> attributes) {
        Object value;
        if ("google".equals(provider)) value = attributes.get("picture");
        else if ("kakao".equals(provider)) {
            Object account = attributes.get("kakao_account");
            if (!(account instanceof Map<?,?> a) || !(a.get("profile") instanceof Map<?,?> p)
                    || Boolean.TRUE.equals(a.get("profile_image_needs_agreement"))
                    || Boolean.TRUE.equals(p.get("is_default_image"))) return new PhotoSource(null);
            value = p.get("profile_image_url");
        } else throw new IllegalArgumentException("Unsupported provider");
        if (!(value instanceof String text) || text.isBlank()) return new PhotoSource(null);
        if (text.length() > 2048) throw new IllegalArgumentException("Invalid photo source");
        URI uri = URI.create(text);
        String host = uri.getHost();
        boolean trusted = host != null && ("google".equals(provider)
                ? host.matches("lh[0-9]+\\.googleusercontent\\.com")
                : Set.of("k.kakaocdn.net","img1.kakaocdn.net","t1.kakaocdn.net").contains(host));
        if (!trusted || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())))
            throw new IllegalArgumentException("Invalid photo source");
        // Kakao can return an HTTP image URL. Only the explicitly trusted host is upgraded.
        if ("http".equals(uri.getScheme())) uri = URI.create("https" + text.substring(4));
        return new PhotoSource(uri);
    }
    @Override public String toString() { return "PhotoSource[redacted]"; }
}
