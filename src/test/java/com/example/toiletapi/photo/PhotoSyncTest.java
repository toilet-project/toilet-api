package com.example.toiletapi.photo;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PhotoSyncTest {
    private final PhotoSettings settings=new PhotoSettings(true,null,null,null,null,null,null);
    @Test void importsConsentedKakaoPhotoOnceAfterSignupCompletion() throws Exception {
        var photos=mock(PhotoService.class);var processor=mock(PhotoProcessor.class);var cdn=mock(PhotoCdnClient.class);
        var provider=mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(cdn);
        var sync=new PhotoSync(settings,photos,processor,provider);
        var ticket=new PhotoService.Ticket(7,0,1);var converted="RIFFtestWEBP".getBytes();
        var attributes=Map.<String,Object>of("kakao_account",Map.of(
                "profile_image_needs_agreement",false,
                "profile",Map.of("profile_image_url","https://k.kakaocdn.net/a.jpg","is_default_image",false)));
        when(photos.signupTicket(7)).thenReturn(ticket);when(processor.process(any())).thenReturn(converted);
        when(photos.saveWithReceipt(eq(ticket),eq(converted),eq("KAKAO_SIGNUP"),eq(PhotoService.NOTICE_VERSION),any())).thenReturn(true);
        when(photos.state(7)).thenReturn(new PhotoService.State(true,true,"12345678-1234-1234-1234-123456789abc"));
        try {
            sync.stageSignup(7,"kakao",attributes);sync.completeSignup(7);sync.completeSignup(7);
            verify(photos,timeout(1000).times(1)).signupTicket(7);
            verify(processor,timeout(1000).times(1)).process(any());
            verify(cdn,timeout(1000).times(1)).warm("12345678-1234-1234-1234-123456789abc");
        } finally {sync.close();}
    }
    @Test void refusesMissingConsentGoogleAndAbsentPhoto() {
        var photos=mock(PhotoService.class);var sync=new PhotoSync(settings,photos,mock(PhotoProcessor.class),mock(org.springframework.beans.factory.ObjectProvider.class));
        try {
            sync.stageSignup(1,"kakao",Map.of("kakao_account",Map.of("profile_image_needs_agreement",true)));
            sync.stageSignup(2,"google",Map.of("picture","https://lh3.googleusercontent.com/a"));
            sync.completeSignup(1);sync.completeSignup(2);verifyNoInteractions(photos);
        } finally {sync.close();}
    }
    @Test void photoProcessingFailureNeverFailsSignupOrRetriesOnLaterCompletion() throws Exception {
        var photos=mock(PhotoService.class);var processor=mock(PhotoProcessor.class);
        var sync=new PhotoSync(settings,photos,processor,mock(org.springframework.beans.factory.ObjectProvider.class));
        var attributes=Map.<String,Object>of("kakao_account",Map.of(
                "profile_image_needs_agreement",false,
                "profile",Map.of("profile_image_url","https://k.kakaocdn.net/a.jpg","is_default_image",false)));
        when(photos.signupTicket(9)).thenReturn(new PhotoService.Ticket(9,0,1));
        when(processor.process(any())).thenThrow(new IllegalStateException("synthetic conversion failure"));
        try {
            sync.stageSignup(9,"kakao",attributes);
            assertDoesNotThrow(()->sync.completeSignup(9));
            assertDoesNotThrow(()->sync.completeSignup(9));
            verify(processor,timeout(1000).times(1)).process(any());
            verify(photos,never()).saveWithReceipt(any(),any(),anyString(),anyString(),any());
        } finally {sync.close();}
    }
}
