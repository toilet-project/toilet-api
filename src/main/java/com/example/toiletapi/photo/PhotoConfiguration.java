package com.example.toiletapi.photo;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PhotoSettings.class)
public class PhotoConfiguration {
    @Bean(destroyMethod="close") PhotoS3Store photoStore(PhotoSettings settings) {
        return new PhotoS3Store(settings);
    }

    static class PhotoS3Store implements PhotoStore, AutoCloseable {
        private final S3Client client;
        private final String bucket;
        PhotoS3Store(PhotoSettings settings) {
            bucket = settings.bucket();
            if (!settings.enabled()) { client = null; return; }
            URI endpoint = URI.create(settings.endpoint());
            if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                    || !endpoint.getHost().matches("[a-f0-9]{32}(?:\\.(?:eu|us))?\\.r2\\.cloudflarestorage\\.com")
                    || endpoint.getUserInfo() != null || endpoint.getPort() != -1
                    || endpoint.getQuery() != null || endpoint.getFragment() != null
                    || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/"))
                    || bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))
                throw new IllegalStateException("Invalid profile photo storage configuration");
            client = S3Client.builder().endpointOverride(endpoint).region(Region.of("auto"))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(settings.accessKeyId(),settings.secretAccessKey())))
                    .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)))
                    .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(8)).apiCallAttemptTimeout(Duration.ofSeconds(6)))
                    .forcePathStyle(true).build();
        }
        private void check(String key) {
            if (client == null || key == null || !key.matches("avatars/[a-f0-9-]{36}\\.webp"))
                throw new IllegalStateException("Photo storage unavailable");
        }
        public void put(String key, byte[] image) {
            check(key);
            client.putObject(r -> r.bucket(bucket).key(key).contentType("image/webp").cacheControl("private, no-store"),RequestBody.fromBytes(image));
        }
        public byte[] get(String key) {
            check(key);
            try (var response = client.getObject(r -> r.bucket(bucket).key(key))) {
                byte[] bytes = response.readNBytes(100_001);
                if (bytes.length > 100_000) throw new IllegalStateException("Invalid photo object");
                return bytes;
            } catch (java.io.IOException e) { throw new IllegalStateException("Photo read failed"); }
        }
        public void delete(String key) { check(key); client.deleteObject(r -> r.bucket(bucket).key(key)); }
        public void close() { if (client != null) client.close(); }
    }
}
