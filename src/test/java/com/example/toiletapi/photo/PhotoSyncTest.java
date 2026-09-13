package com.example.toiletapi.photo;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PhotoSyncTest {
    private final PhotoSettings settings=new PhotoSettings(true,null,null,null,null,null,null);
    @Test void importsConsentedKakaoPhotoOnceAfterSignupCompletion() throws Exception {
        var photos=mock(PhotoService.class);var processor=mock(PhotoProcessor.class);var sync=new PhotoSync(settings,photos,processor);
        var ticket=new PhotoService.Ticket(7,0,1);var converted="RIFFtestWEBP".getBytes();
        var attributes=Map.<String,Object>of("kakao_account",Map.of(
                "profile_image_needs_agreement",false,
                "profile",Map.of("profile_image_url","https://k.kakaocdn.net/a.jpg","is_default_image",false)));
        when(photos.signupTicket(7)).thenReturn(ticket);when(processor.process(any())).thenReturn(converted);
        when(photos.saveWithReceipt(eq(ticket),eq(converted),eq("KAKAO_SIGNUP"),eq(PhotoService.NOTICE_VERSION),any())).thenReturn(true);
        try {
            sync.stageSignup(7,"kakao",attributes);sync.completeSignup(7);sync.completeSignup(7);
            verify(photos,timeout(1000).times(1)).signupTicket(7);
            verify(processor,timeout(1000).times(1)).process(any());
        } finally {sync.close();}
    }
    @Test void refusesMissingConsentGoogleAndAbsentPhoto() {
        var photos=mock(PhotoService.class);var sync=new PhotoSync(settings,photos,mock(PhotoProcessor.class));
        try {
            sync.stageSignup(1,"kakao",Map.of("kakao_account",Map.of("profile_image_needs_agreement",true)));
            sync.stageSignup(2,"google",Map.of("picture","https://lh3.googleusercontent.com/a"));
            sync.completeSignup(1);sync.completeSignup(2);verifyNoInteractions(photos);
        } finally {sync.close();}
    }
}
