package com.example.toiletapi.photo;

import com.example.toiletapi.auth.controller.AccountRecoveryController;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PhotoController {
    private final PhotoService photos;
    private final PhotoProcessor processor;
    private final boolean reviewsEnabled;
    private final PhotoCdnSettings cdn;
    public PhotoController(PhotoService photos, PhotoProcessor processor,
                           @org.springframework.beans.factory.annotation.Value("${reviews.enabled:false}") boolean reviewsEnabled,PhotoCdnSettings cdn) {
        this.photos=photos;this.processor=processor;this.reviewsEnabled=reviewsEnabled;this.cdn=cdn;
    }
    @GetMapping("/api/v1/auth/me/photo") public PhotoService.State state(@AuthenticationPrincipal Jwt jwt) {return photos.state(Long.parseLong(jwt.getSubject()));}
    @PatchMapping(value="/api/v1/auth/me/photo",consumes=MediaType.APPLICATION_JSON_VALUE)
    public PhotoService.State update(@AuthenticationPrincipal Jwt jwt,@RequestBody Preference input,HttpServletRequest request) {
        AccountRecoveryController.requireTrustedOrigin(request);
        if(input.publicPhoto()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return photos.visibility(Long.parseLong(jwt.getSubject()),input.publicPhoto());
    }
    @PutMapping(value="/api/v1/auth/me/photo", consumes={MediaType.IMAGE_JPEG_VALUE,MediaType.IMAGE_PNG_VALUE,"image/webp"})
    public PhotoService.State upload(@AuthenticationPrincipal Jwt jwt,HttpServletRequest request) throws Exception {
        AccountRecoveryController.requireTrustedOrigin(request);
        long declared=request.getContentLengthLong();
        if(declared<=0 || declared>2L*1024*1024) throw new ResponseStatusException(declared>0?HttpStatus.PAYLOAD_TOO_LARGE:HttpStatus.LENGTH_REQUIRED);
        byte[] original=request.getInputStream().readNBytes(2*1024*1024+1);
        try {
            if(original.length!=declared || original.length>2*1024*1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
            var ticket=photos.uploadTicket(Long.parseLong(jwt.getSubject()));
            byte[] converted=processor.convert(original);
            try {
                if(!photos.saveWithReceipt(ticket,converted,"DIRECT_UPLOAD",PhotoService.NOTICE_VERSION,
                        com.example.toiletapi.global.time.KoreanTime.now())) throw new ResponseStatusException(HttpStatus.CONFLICT);
                return photos.state(Long.parseLong(jwt.getSubject()));
            } finally {java.util.Arrays.fill(converted,(byte)0);}
        } finally {java.util.Arrays.fill(original,(byte)0);}
    }
    @DeleteMapping("/api/v1/auth/me/photo")
    public PhotoService.State delete(@AuthenticationPrincipal Jwt jwt,HttpServletRequest request) {
        AccountRecoveryController.requireTrustedOrigin(request);
        return photos.delete(Long.parseLong(jwt.getSubject()));
    }
    @GetMapping("/api/v1/auth/me/photo/image") public ResponseEntity<byte[]> own(@AuthenticationPrincipal Jwt jwt,@RequestParam String version,
                                                                                  @RequestHeader(value=HttpHeaders.IF_NONE_MATCH,required=false) String ifNoneMatch) {
        if(!version.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return privateImage(photos.ownImage(Long.parseLong(jwt.getSubject()),version,ifNoneMatch));
    }
    @GetMapping("/api/v1/toilets/{toilet}/reviews/{review}/photo") public ResponseEntity<byte[]> review(@PathVariable long toilet,@PathVariable long review) {
        if(!reviewsEnabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return noStoreImage(photos.reviewImage(toilet,review));
    }
    @GetMapping("/api/v1/profile-photos/{version}.webp")
    public ResponseEntity<byte[]> publicImage(@PathVariable String version,@RequestHeader(value=HttpHeaders.IF_NONE_MATCH,required=false) String ifNoneMatch) {
        if(!reviewsEnabled || !version.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        PhotoService.Image image=photos.publicImage(version,ifNoneMatch);
        String etag='"'+image.hash()+'"';
        var headers=publicHeaders(etag);
        if(image.notModified()) return new ResponseEntity<>(null,headers,HttpStatus.NOT_MODIFIED);
        return new ResponseEntity<>(image.bytes(),headers,HttpStatus.OK);
    }
    private ResponseEntity<byte[]> privateImage(PhotoService.Image image) {
        var response=ResponseEntity.status(image.notModified()?HttpStatus.NOT_MODIFIED:HttpStatus.OK)
                .contentType(MediaType.parseMediaType("image/webp"))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate().mustRevalidate())
                .header(HttpHeaders.ETAG,'"'+image.hash()+'"')
                .header("CDN-Cache-Control","no-store").header("Cloudflare-CDN-Cache-Control","no-store");
        return response.body(image.bytes());
    }
    private ResponseEntity<byte[]> noStoreImage(PhotoService.Image image) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("image/webp")).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ETAG,'"'+image.hash()+'"').body(image.bytes());
    }
    private HttpHeaders publicHeaders(String etag) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.parseMediaType("image/webp"));headers.setETag(etag);
        headers.setCacheControl(cdn.enabled()?"public, max-age=0, must-revalidate":"no-store");
        headers.set("CDN-Cache-Control",cdn.enabled()?"public, max-age=300, must-revalidate":"no-store");
        headers.set("Cloudflare-CDN-Cache-Control",cdn.enabled()?"public, max-age=300, must-revalidate":"no-store");
        headers.set("X-Content-Type-Options","nosniff");headers.set("X-Robots-Tag","noindex, noimageindex");
        return headers;
    }
    public record Preference(Boolean publicPhoto) { }
}
