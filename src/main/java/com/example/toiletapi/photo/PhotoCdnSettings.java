package com.example.toiletapi.photo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("profile-photo.cdn")
public record PhotoCdnSettings(boolean enabled,String zoneId,String token,String publicOrigin) { }
