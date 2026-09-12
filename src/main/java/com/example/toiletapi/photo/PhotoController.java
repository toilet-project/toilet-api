package com.example.toiletapi.photo;

import com.example.toiletapi.auth.controller.AccountRecoveryController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PhotoController {
    private final PhotoService photos;
    private final boolean reviewsEnabled;
    public PhotoController(PhotoService photos,@org.springframework.beans.factory.annotation.Value("${reviews.enabled:false}") boolean reviewsEnabled) {this.photos=photos;this.reviewsEnabled=reviewsEnabled;}
    @GetMapping("/api/v1/auth/me/photo") public PhotoService.State state(@AuthenticationPrincipal Jwt jwt) {return photos.state(Long.parseLong(jwt.getSubject()));}
    @PatchMapping(value="/api/v1/auth/me/photo",consumes=MediaType.APPLICATION_JSON_VALUE)
    public PhotoService.State update(@AuthenticationPrincipal Jwt jwt,@RequestBody Preference input,HttpServletRequest request) {
        AccountRecoveryController.requireTrustedOrigin(request);
        if(input.useSocial()==null || input.publicPhoto()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return photos.update(Long.parseLong(jwt.getSubject()),input.useSocial(),input.publicPhoto());
    }
    @GetMapping("/api/v1/auth/me/photo/image") public ResponseEntity<byte[]> own(@AuthenticationPrincipal Jwt jwt,@RequestParam String version) {
        if(!version.matches("[a-f0-9-]{36}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return image(photos.ownImage(Long.parseLong(jwt.getSubject()),version));
    }
    @GetMapping("/api/v1/toilets/{toilet}/reviews/{review}/photo") public ResponseEntity<byte[]> review(@PathVariable long toilet,@PathVariable long review) {
        if(!reviewsEnabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return image(photos.reviewImage(toilet,review));
    }
    private ResponseEntity<byte[]> image(byte[] bytes) {return ResponseEntity.ok().contentType(MediaType.parseMediaType("image/webp")).cacheControl(CacheControl.noStore()).body(bytes);}
    public record Preference(Boolean useSocial,Boolean publicPhoto) { }
}
