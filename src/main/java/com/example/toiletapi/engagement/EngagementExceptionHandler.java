package com.example.toiletapi.engagement;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=EngagementController.class)
public class EngagementExceptionHandler {
    @ExceptionHandler(EngagementFailure.class)
    ResponseEntity<?> failure(EngagementFailure failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("error",Map.of("code",failure.code(),"message",failure.getMessage())));
    }
}
