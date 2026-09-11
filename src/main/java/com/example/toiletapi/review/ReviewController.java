package com.example.toiletapi.review;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.example.toiletapi.review.ReviewModels.*;

@RestController
public class ReviewController {
    private final ReviewService service;
    public ReviewController(ReviewService service) {this.service=service;}
    @PostMapping("/api/v1/reviews") @ResponseStatus(HttpStatus.CREATED)
    public Item create(@AuthenticationPrincipal Jwt jwt,@RequestBody Create request,
                       @RequestHeader("Idempotency-Key") String key) {return service.create(actor(jwt),request,key);}
    @GetMapping("/api/v1/reviews/me")
    public Page mine(@AuthenticationPrincipal Jwt jwt,
                     @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
                     @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
                     @RequestParam(required=false) String cursor,@RequestParam(defaultValue="10") int size) {
        return service.mine(actor(jwt),from,to,cursor,size);
    }
    @GetMapping("/api/v1/reviews/{id}")
    public Item detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) {return service.mineDetail(actor(jwt),id);}
    @GetMapping("/api/v1/reviews/creation-status")
    public CreationStatus creationStatus(@AuthenticationPrincipal Jwt jwt,@RequestParam long toiletId) {
        return service.creationStatus(actor(jwt),toiletId);
    }
    @PatchMapping("/api/v1/reviews/{id}")
    public Item edit(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestBody Edit request) {return service.edit(actor(jwt),id,request);}
    @PostMapping("/api/v1/reviews/{id}/detach-author")
    public Detached detach(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestBody Detach request) {return service.detach(actor(jwt),id,request);}
    @GetMapping("/api/v1/toilets/{id}/reviews")
    public Page publicPage(@PathVariable long id,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="10") int size) {return service.publicPage(id,cursor,size);}
    @GetMapping("/api/v1/toilets/{id}/reviews/summary")
    public Summary summary(@PathVariable long id) {return service.summary(id);}
    private static ReviewService.Actor actor(Jwt jwt) {
        try {
            long id=Long.parseLong(jwt.getSubject());Number version=jwt.getClaim("auth_version");
            if(id<=0)throw new IllegalArgumentException();
            return new ReviewService.Actor(id,version==null?0:version.longValue());
        } catch(RuntimeException e) {throw new ReviewFailure(401,"AUTHENTICATION_REQUIRED","로그인을 다시 확인해 주세요.");}
    }
}
