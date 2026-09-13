package com.example.toiletapi.photo;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PhotoConfigurationTest {
    private static final String ACCOUNT = "a".repeat(32);

    @Test void enabledStorageIsPinnedToTheUsJurisdictionAndDedicatedBucket() {
        var valid = settings("https://" + ACCOUNT + ".us.r2.cloudflarestorage.com", "geupddong-profile-photos-us");
        assertDoesNotThrow(() -> new PhotoConfiguration.PhotoS3Store(valid).close());

        for (var endpoint : new String[]{
                "https://" + ACCOUNT + ".r2.cloudflarestorage.com",
                "https://" + ACCOUNT + ".eu.r2.cloudflarestorage.com",
                "https://evil.example"})
            assertThrows(IllegalStateException.class,
                    () -> new PhotoConfiguration.PhotoS3Store(settings(endpoint, "geupddong-profile-photos-us")));

        assertThrows(IllegalStateException.class,
                () -> new PhotoConfiguration.PhotoS3Store(settings(
                        "https://" + ACCOUNT + ".us.r2.cloudflarestorage.com", "geupddong-profile-photos")));
    }

    private PhotoSettings settings(String endpoint, String bucket) {
        return new PhotoSettings(true, endpoint, bucket, "access", "secret", "python3", "converter.py");
    }
}
