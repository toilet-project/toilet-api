package com.example.toiletapi.review;

import com.example.toiletapi.global.exception.ApiErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes=ReviewController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReviewExceptionHandler {
    @ExceptionHandler(ReviewFailure.class)
    ResponseEntity<?> failure(ReviewFailure error) {
        var status=error.creationStatus();
        return ResponseEntity.status(error.status()).body(status==null?ApiErrorResponse.of(error.code(),error.getMessage())
                :java.util.Map.of("error",new ReviewModels.CreationConflict(error.code(),error.getMessage(),status.existingReviewId(),status.nextAllowedAt())));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,ServletRequestBindingException.class,MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiErrorResponse> malformed(Exception ignored) {
        // Jackson/binding diagnostics can include device coordinates and free text; do not echo them.
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("REVIEW_INVALID_REQUEST","리뷰 입력 형식을 확인해 주세요."));
    }
}
