package com.example.toiletapi.photo;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class PhotoSourceTest {
    @Test void allowsOnlyConsentedKakaoHostAndUpgradesHttp() {
        var kakao=attributes("http://k.kakaocdn.net/a.jpg",false,false);
        assertEquals("https://k.kakaocdn.net/a.jpg",PhotoSource.from("kakao",kakao).uri().toString());

        for(String url:List.of("https://127.0.0.1/a","http://169.254.169.254/a",
                "https://k.kakaocdn.net.evil.test/a","https://k.kakaocdn.net@evil.test/a",
                "https://k.kakaocdn.net:8443/a","file:///etc/passwd","https://k.kakaocdn.net/a#secret"))
            assertThrows(IllegalArgumentException.class,()->PhotoSource.from("kakao",attributes(url,false,false)));
        assertThrows(IllegalArgumentException.class,()->PhotoSource.from("google",Map.of("picture","https://lh3.googleusercontent.com/a")));
    }

    @Test void missingConsentDeniedOrDefaultIsAbsent() {
        assertNull(PhotoSource.from("kakao",Map.of()).uri());
        assertNull(PhotoSource.from("kakao",attributes("https://k.kakaocdn.net/a",true,false)).uri());
        assertNull(PhotoSource.from("kakao",attributes("https://k.kakaocdn.net/a",false,true)).uri());
        var missingDecision=Map.<String,Object>of("kakao_account",Map.of("profile",Map.of("profile_image_url","https://k.kakaocdn.net/a")));
        assertNull(PhotoSource.from("kakao",missingDecision).uri());
    }

    private Map<String,Object> attributes(String url,boolean needsAgreement,boolean defaultImage) {
        return Map.of("kakao_account",Map.of("profile_image_needs_agreement",needsAgreement,
                "profile",Map.of("is_default_image",defaultImage,"profile_image_url",url)));
    }

    @Test void limitsChunkedBodyBeforeAccumulatingIt() {
        var body=new PhotoProcessor.LimitedBody();boolean[] cancelled={false};
        body.onSubscribe(new Flow.Subscription(){public void request(long n){} public void cancel(){cancelled[0]=true;}});
        body.onNext(List.of(ByteBuffer.allocate(2*1024*1024+1)));
        assertTrue(cancelled[0]);assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }
}
