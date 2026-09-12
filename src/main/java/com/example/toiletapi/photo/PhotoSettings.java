package com.example.toiletapi.photo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("profile-photo")
public record PhotoSettings(boolean enabled, String endpoint, String bucket, String accessKeyId,
                            String secretAccessKey, String python, String converter) { }
