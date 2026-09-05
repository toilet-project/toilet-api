package com.example.toiletapi.global.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.example.toiletapi.policy.service.PolicyConsentRequiredException;

/**
 * 공통 API 예외 응답을 처리합니다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(com.example.toiletapi.geocoding.AddressLookupException.class)
    public ResponseEntity<ApiErrorResponse> handleAddressLookup(com.example.toiletapi.geocoding.AddressLookupException exception) {
        return ResponseEntity.status(503).body(ApiErrorResponse.of("ADDRESS_LOOKUP_UNAVAILABLE", exception.getMessage()));
    }

    /**
     * 잘못된 요청 파라미터를 HTTP 400 응답으로 변환합니다.
     *
     * @param exception 발생한 예외
     * @return 표준 오류 응답
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgumentException(IllegalArgumentException exception) {
        ApiErrorResponse response = ApiErrorResponse.of("INVALID_REQUEST", exception.getMessage());

        return ResponseEntity.badRequest().body(response);
    }

    /**
     * 존재하지 않는 화장실 요청을 HTTP 404 응답으로 변환합니다.
     *
     * @param exception 발생한 예외
     * @return 표준 오류 응답
     */
    @ExceptionHandler(ToiletNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleToiletNotFoundException(ToiletNotFoundException exception) {
        ApiErrorResponse response = ApiErrorResponse.of("TOILET_NOT_FOUND", exception.getMessage());

        return ResponseEntity.status(404).body(response);
    }

    @ExceptionHandler(PolicyConsentRequiredException.class)
    public ResponseEntity<ApiErrorResponse> handlePolicyConsentRequired(PolicyConsentRequiredException exception) {
        return ResponseEntity.status(403)
                .body(ApiErrorResponse.of("POLICY_CONSENT_REQUIRED", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalState(IllegalStateException exception) {
        return ResponseEntity.status(403)
                .body(ApiErrorResponse.of("ACCOUNT_NOT_AVAILABLE", exception.getMessage()));
    }
}
