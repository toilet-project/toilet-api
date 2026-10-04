package com.example.toiletapi.growth;

import com.example.toiletapi.global.exception.ApiErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes={GrowthController.class,AdminGrowthController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GrowthExceptionHandler {
    @ExceptionHandler(GrowthFailure.class)
    ResponseEntity<ApiErrorResponse> failure(GrowthFailure error) {
        return ResponseEntity.status(error.status())
                .body(ApiErrorResponse.of(error.code(),error.getMessage()));
    }
}
