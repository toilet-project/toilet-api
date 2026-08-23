package com.example.toiletapi.global.exception;

/**
 * API 오류 응답 형식입니다.
 *
 * @param status HTTP 상태 코드
 * @param errorCode 오류 코드
 * @param message 오류 상세 메시지
 */
public record ApiErrorResponse(
        int status,
        String errorCode,
        String message
) {
}
