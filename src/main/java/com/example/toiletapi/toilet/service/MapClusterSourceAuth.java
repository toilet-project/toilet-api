package com.example.toiletapi.toilet.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Authenticate the single full-catalog read used by the WEB edge cache. */
@Component
public class MapClusterSourceAuth {
    private static final String PATH = "/api/v1/toilets/map-cluster-points";
    private static final long MAX_SKEW_SECONDS = 300;
    private final String primarySecret;
    private final String previewSecret;
    private final Clock clock;

    @Autowired
    public MapClusterSourceAuth(@Value("${web-cache.secret:}") String primarySecret,
            @Value("${web-cache.cluster-preview-secret:}") String previewSecret) {
        this(primarySecret, previewSecret, Clock.systemUTC());
    }

    MapClusterSourceAuth(String primarySecret, String previewSecret, Clock clock) {
        this.primarySecret = primarySecret;
        this.previewSecret = previewSecret;
        this.clock = clock;
    }

    public void requireValid(String timestamp, String signature) {
        if (!configured(primarySecret) && !configured(previewSecret))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cluster source authentication unavailable");
        if (timestamp == null || !timestamp.matches("[0-9]{10}") || signature == null
                || !signature.matches("[a-f0-9]{64}"))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid cluster source signature");
        final long seconds;
        try { seconds = Long.parseLong(timestamp); }
        catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid cluster source timestamp");
        }
        if (Math.abs(clock.instant().getEpochSecond() - seconds) > MAX_SKEW_SECONDS)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Expired cluster source signature");
        try {
            byte[] supplied = java.util.HexFormat.of().parseHex(signature);
            if (!matches(primarySecret, timestamp, supplied) && !matches(previewSecret, timestamp, supplied))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid cluster source signature");
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cluster source authentication unavailable");
        }
    }

    private static boolean configured(String secret) {
        return secret != null && secret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    private static boolean matches(String secret, String timestamp, byte[] supplied)
            throws java.security.GeneralSecurityException {
        if (!configured(secret)) return false;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal(("v1\nGET\n" + PATH + "\n" + timestamp)
                .getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(expected, supplied);
    }
}
