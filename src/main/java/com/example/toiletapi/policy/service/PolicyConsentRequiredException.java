package com.example.toiletapi.policy.service;

public class PolicyConsentRequiredException extends RuntimeException {
    public PolicyConsentRequiredException() {
        super("필수 약관 동의가 필요합니다.");
    }
}
