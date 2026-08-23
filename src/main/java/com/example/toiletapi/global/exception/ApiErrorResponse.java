package com.example.toiletapi.global.exception;

/**
 * API 오류 응답 형식입니다.
 *
 * @param error 오류 상세 정보
 */
public record ApiErrorResponse(
        ApiError error
) {

    /**
     * 오류 코드와 메시지로 표준 오류 응답을 생성합니다.
     *
     * @param code 오류 코드
     * @param message 오류 상세 메시지
     * @return 표준 오류 응답
     */
    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(new ApiError(code, message));
    }

    /**
     * API 오류의 식별 코드와 사용자 메시지입니다.
     *
     * @param code 오류 코드
     * @param message 오류 상세 메시지
     */
    public record ApiError(String code, String message) {
    }
}
