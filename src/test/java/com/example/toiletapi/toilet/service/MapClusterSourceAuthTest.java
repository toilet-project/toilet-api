package com.example.toiletapi.toilet.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class MapClusterSourceAuthTest {
    private static final String SECRET = "test-cluster-source-secret-at-least-32-bytes";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_780_000_000L), ZoneOffset.UTC);

    @Test
    void acceptsFreshSignedWorkerRead() throws Exception {
        var auth = new MapClusterSourceAuth(SECRET, "", CLOCK);
        assertDoesNotThrow(() -> auth.requireValid("1780000000", signature("1780000000")));
        assertDoesNotThrow(() -> new MapClusterSourceAuth("", SECRET, CLOCK)
                .requireValid("1780000000", signature("1780000000")));
    }

    @Test
    void rejectsUnsignedTamperedAndExpiredReadsBeforeDatabaseAccess() throws Exception {
        var auth = new MapClusterSourceAuth(SECRET, "", CLOCK);
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> auth.requireValid(null, null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> auth.requireValid("1780000000", "0".repeat(64))).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> auth.requireValid("1779999000", signature("1779999000"))).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> new MapClusterSourceAuth("", "", CLOCK).requireValid("1780000000", signature("1780000000")))
                .getStatusCode());
    }

    private static String signature(String timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(
                ("v1\nGET\n/api/v1/toilets/map-cluster-points\n" + timestamp).getBytes(StandardCharsets.UTF_8)));
    }
}
