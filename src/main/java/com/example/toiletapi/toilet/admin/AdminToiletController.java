package com.example.toiletapi.toilet.admin;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.example.toiletapi.toilet.admin.AdminToiletModels.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/v1/toilets")
public class AdminToiletController {
    private final AdminToiletService service;

    @GetMapping
    public Page<Item> search(@RequestParam(defaultValue = "") String keyword,
                             @RequestParam(defaultValue = "") String sidoCode,
                             @RequestParam(defaultValue = "") String sigunguCode,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "15") int size) {
        return service.search(keyword, sidoCode, sigunguCode, page, size);
    }

    @GetMapping("/suggestions")
    public List<Suggestion> suggestions(@RequestParam(defaultValue = "") String keyword,
                                        @RequestParam(defaultValue = "8") int limit) {
        return service.suggestions(keyword, limit);
    }

    @GetMapping("/regions")
    public List<RegionOption> regions() {
        return service.regions();
    }

    @GetMapping("/{id}")
    public Detail detail(@PathVariable long id) {
        return service.detail(id);
    }

    @PutMapping("/{id}")
    public Detail update(@PathVariable long id, @Valid @RequestBody UpdateRequest request,
                         @AuthenticationPrincipal Jwt jwt) {
        return service.update(Long.parseLong(jwt.getSubject()), id, request);
    }
}
