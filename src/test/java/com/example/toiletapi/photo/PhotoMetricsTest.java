package com.example.toiletapi.photo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PhotoMetricsTest {
    @Test void exposesObjectAndDeletionBacklogWithoutMemberData() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:photoMetrics-" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1"));
        jdbc.execute("CREATE TABLE profile_photo_object(object_key VARCHAR(100) PRIMARY KEY,created_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE profile_photo(object_key VARCHAR(100))");
        var old = LocalDateTime.now().minusMinutes(10);
        jdbc.update("INSERT INTO profile_photo_object VALUES('avatars/linked.webp',?)", old);
        jdbc.update("INSERT INTO profile_photo_object VALUES('avatars/orphan.webp',?)", old);
        jdbc.update("INSERT INTO profile_photo VALUES('avatars/linked.webp')");
        var registry = new SimpleMeterRegistry();
        var metrics = new PhotoMetrics(new PhotoSettings(true,null,null,null,null,null,null), jdbc, registry);

        metrics.conversion(true); metrics.conversion(false);
        metrics.put(true); metrics.get(false); metrics.delete(true);
        metrics.refresh();

        assertEquals(2, registry.get("profile.photo.storage.objects").gauge().value());
        assertEquals(1, registry.get("profile.photo.deletion.pending").gauge().value());
        assertTrue(registry.get("profile.photo.deletion.oldest.seconds").gauge().value() >= 300);
        assertEquals(1, registry.get("profile.photo.conversions").tag("result", "success").counter().count());
        assertEquals(1, registry.get("profile.photo.conversions").tag("result", "failure").counter().count());
        assertEquals(1, registry.get("profile.photo.storage.operations").tags("operation", "put", "result", "success").counter().count());
        assertEquals(1, registry.get("profile.photo.storage.operations").tags("operation", "get", "result", "failure").counter().count());
        assertEquals(1, registry.get("profile.photo.storage.operations").tags("operation", "delete", "result", "success").counter().count());
        assertEquals(0, registry.counter("profile.photo.metrics.refresh.failures").count());
    }

    @Test void disabledFeatureDoesNotQueryStorageTables() {
        var registry = new SimpleMeterRegistry();
        var metrics = new PhotoMetrics(new PhotoSettings(false,null,null,null,null,null,null), null, registry);
        metrics.refresh();
        assertEquals(0, registry.get("profile.photo.storage.objects").gauge().value());
        assertEquals(0, registry.get("profile.photo.deletion.pending").gauge().value());
    }
}
