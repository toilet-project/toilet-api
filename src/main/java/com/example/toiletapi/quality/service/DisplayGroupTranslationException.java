package com.example.toiletapi.quality.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class DisplayGroupTranslationException extends RuntimeException {
    public DisplayGroupTranslationException() {
        super("영문 그룹명을 자동 생성하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }
}
