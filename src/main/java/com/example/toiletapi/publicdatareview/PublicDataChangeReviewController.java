package com.example.toiletapi.publicdatareview;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.example.toiletapi.publicdatareview.PublicDataChangeReviewModels.*;

@RestController
@RequestMapping("/api/admin/v1/public-data-change-reviews")
public class PublicDataChangeReviewController {
    private final PublicDataChangeReviewService service;

    public PublicDataChangeReviewController(PublicDataChangeReviewService service) {
        this.service = service;
    }

    @GetMapping
    public Page search(@RequestParam(required = false) Status status,
                       @RequestParam(defaultValue = "") String keyword,
                       @RequestParam(required = false) Integer receivedWithinDays,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "15") int size,
                       @RequestParam(defaultValue = "lastReceivedAt,desc") String sort) {
        return service.search(status, keyword, receivedWithinDays, page, size, sort);
    }

    @GetMapping("/{id}")
    public Detail detail(@PathVariable long id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/decisions")
    public Detail decide(@PathVariable long id, @Valid @RequestBody DecisionRequest request,
                         @AuthenticationPrincipal Jwt jwt) {
        return service.decide(Long.parseLong(jwt.getSubject()), id, request);
    }
}
