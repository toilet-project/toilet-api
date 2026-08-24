package com.example.toiletapi.global.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 공통 API 예외 응답을 처리합니다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

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
}
