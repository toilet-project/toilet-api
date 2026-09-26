package com.example.toiletapi.places;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/places")
public class PlaceSearchController {
    private final PlaceSearchService service;
    public PlaceSearchController(PlaceSearchService service) { this.service = service; }

    public record SearchRequest(@NotBlank @Size(max = 160) String query) {}

    @PostMapping(value = "/search", consumes = "application/json")
    public ResponseEntity<PlaceSearchService.Results> search(@Valid @RequestBody SearchRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Robots-Tag", "noindex")
                .body(service.search(request.query()));
    }
}
