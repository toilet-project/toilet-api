package com.example.toiletapi.toilet.openinghours;

import com.example.toiletapi.toilet.openinghours.OpeningHoursModels.BackfillResult;
import com.example.toiletapi.toilet.openinghours.OpeningHoursModels.ConfirmRequest;
import com.example.toiletapi.toilet.openinghours.OpeningHoursModels.View;
import com.example.toiletapi.global.exception.ToiletNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/v1/opening-hours")
public class AdminOpeningHoursController {
    private final OpeningHoursService service;

    @PostMapping("/normalize")
    public BackfillResult normalize(@RequestParam(defaultValue = "0") long afterId,
                                    @RequestParam(defaultValue = "500") int limit) {
        return service.normalizeAfter(afterId, limit);
    }

    @GetMapping("/{toiletId}")
    public View detail(@PathVariable long toiletId) {
        return service.find(toiletId).orElseThrow(() -> new ToiletNotFoundException(toiletId));
    }

    @PutMapping("/{toiletId}")
    public View confirm(@PathVariable long toiletId, @RequestBody ConfirmRequest request,
                        @AuthenticationPrincipal Jwt jwt) {
        return service.confirm(Long.parseLong(jwt.getSubject()), toiletId, request);
    }
}
