package com.example.toiletapi.global.response;

/**
 * 성공 API 응답을 감싸는 공통 형식입니다.
 *
 * @param data 실제 응답 데이터
 * @param <T> 응답 데이터 타입
 */
public record ApiResponse<T>(T data) {

    /**
     * 실제 데이터를 공통 성공 응답으로 감쌉니다.
     *
     * @param data 실제 응답 데이터
     * @return 공통 성공 응답
     * @param <T> 응답 데이터 타입
     */
    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data);
    }
}
