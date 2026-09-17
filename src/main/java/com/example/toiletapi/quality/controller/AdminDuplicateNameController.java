package com.example.toiletapi.quality.controller;

import com.example.toiletapi.quality.service.DuplicateNameService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import static com.example.toiletapi.quality.dto.DuplicateNameModels.*;

@RestController
@RequestMapping("/api/admin/v1/duplicate-names")
@ConditionalOnProperty(name="app.duplicate-facilities.enabled",havingValue="true")
public class AdminDuplicateNameController {
    private final DuplicateNameService service;
    public AdminDuplicateNameController(DuplicateNameService service){this.service=service;}
    @GetMapping public Page groups(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="false")boolean includeHidden,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="ALL")Comparison match,@RequestParam(defaultValue="VISIBLE")WorkVisibility workVisibility){return service.groups(keyword,includeHidden,page,size,match,Long.parseLong(jwt.getSubject()),workVisibility);}
    @PostMapping("/work-visibility") public WorkVisibilityResult workVisibility(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody WorkVisibilityRequest request){return service.setWorkVisibility(Long.parseLong(jwt.getSubject()),request);}
    @GetMapping("/facilities") public List<Facility> facilities(@RequestParam String name){return service.facilities(name);}
    @GetMapping("/coordinate-facilities") public List<Facility> coordinateFacilities(@RequestParam java.math.BigDecimal latitude,@RequestParam java.math.BigDecimal longitude){return service.coordinateFacilities(latitude,longitude);}
    @PostMapping("/coordinate-hide") public List<Facility> coordinateHide(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody HideRequest request){return service.hideAtCoordinates(Long.parseLong(jwt.getSubject()),request);}
    @GetMapping("/{id}/history") public List<Event> history(@PathVariable long id){return service.history(id);}
    @PostMapping("/hide") public List<Facility> hide(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody HideRequest request){return service.hide(Long.parseLong(jwt.getSubject()),request);}
    @PostMapping("/{id}/restore") public List<Facility> restore(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@Valid @RequestBody RestoreRequest request){return service.restore(Long.parseLong(jwt.getSubject()),id,request);}
}
