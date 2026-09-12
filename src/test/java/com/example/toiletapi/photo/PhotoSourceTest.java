package com.example.toiletapi.photo;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
class PhotoSourceTest {
    @Test void allowsOnlyProviderHostsAndUpgradesKakao() {
        assertEquals("https://lh3.googleusercontent.com/a/photo",PhotoSource.from("google",Map.of("picture","https://lh3.googleusercontent.com/a/photo")).uri().toString());
        var kakao=Map.<String,Object>of("kakao_account",Map.of("profile",Map.of("profile_image_url","http://k.kakaocdn.net/a.jpg")));
        assertEquals("https://k.kakaocdn.net/a.jpg",PhotoSource.from("kakao",kakao).uri().toString());
        for(String url:List.of("https://127.0.0.1/a","http://169.254.169.254/a","https://lh3.googleusercontent.com.evil.test/a","https://lh3.googleusercontent.com@evil.test/a","https://lh3.googleusercontent.com:8443/a","file:///etc/passwd","https://lh3.googleusercontent.com/a#secret"))
            assertThrows(IllegalArgumentException.class,()->PhotoSource.from("google",Map.of("picture",url)));
    }
    @Test void missingOrDeniedOrDefaultIsAbsent() {
        assertNull(PhotoSource.from("google",Map.of()).uri());
        assertNull(PhotoSource.from("kakao",Map.of("kakao_account",Map.of("profile",Map.of("is_default_image",true,"profile_image_url","https://k.kakaocdn.net/a")))).uri());
        assertNull(PhotoSource.from("kakao",Map.of("kakao_account",Map.of("profile_image_needs_agreement",true,"profile",Map.of("profile_image_url","https://k.kakaocdn.net/a")))).uri());
    }
    @Test void limitsChunkedBodyBeforeAccumulatingIt() {
        var body=new PhotoProcessor.LimitedBody();boolean[] cancelled={false};
        body.onSubscribe(new Flow.Subscription(){public void request(long n){} public void cancel(){cancelled[0]=true;}});
        body.onNext(List.of(ByteBuffer.allocate(2*1024*1024+1)));
        assertTrue(cancelled[0]);assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }
}
