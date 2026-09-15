package com.example.toiletapi.analytics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/analytics")
public class AnalyticsEventController {

    private final AnalyticsEventService service;

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Boolean> collect(@Valid @RequestBody AnalyticsEventRequest event, HttpServletRequest request) {
        service.collect(event, request);
        return Map.of("accepted", true);
    }
}
