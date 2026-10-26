package com.example.toiletapi.growth;

import com.example.toiletapi.global.exception.ApiErrorResponse;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Never serialize binding/JDBC diagnostics: they can contain private search parameters. */
@RestControllerAdvice(assignableTypes=AdminGrowthRankingController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminGrowthRankingExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> access(ResponseStatusException failure) {
        int status=failure.getStatusCode().value();return error(status,status==401?"AUTHENTICATION_REQUIRED":"ACCESS_DENIED","관리자 로그인을 확인해 주세요.");
    }
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class})
    ResponseEntity<ApiErrorResponse> invalid(Exception ignored) {return error(400,"XP_RANKING_INVALID_REQUEST","경험치 조회 조건을 확인해 주세요.");}
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> unavailable(Exception ignored) {return error(503,"XP_RANKING_UNAVAILABLE","경험치 조회를 일시적으로 불러올 수 없습니다.");}
    private static ResponseEntity<ApiErrorResponse> error(int status,String code,String message) {
        return ResponseEntity.status(status).header("Cache-Control","private, no-store").body(ApiErrorResponse.of(code,message));
    }
}
