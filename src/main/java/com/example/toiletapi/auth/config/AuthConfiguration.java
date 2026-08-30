package com.example.toiletapi.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AdminBootstrapProperties.class, AuthTokenProperties.class})
public class AuthConfiguration { }
