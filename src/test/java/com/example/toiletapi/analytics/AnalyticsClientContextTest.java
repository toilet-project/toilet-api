package com.example.toiletapi.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AnalyticsClientContextTest {
    @ParameterizedTest
    @CsvSource(delimiter='|', value={
        "Mozilla iPhone AppleWebKit Safari/604 KAKAOTALK/26|KAKAOTALK|Safari",
        "Mozilla iPhone AppleWebKit Line/15|LINE|Other",
        "Mozilla iPhone AppleWebKit NAVER(inapp; search)|NAVER_APP|Other",
        "Mozilla Android Chrome/130 Safari/537 Instagram 300|INSTAGRAM|Chrome",
        "Mozilla iPhone AppleWebKit [FBAN/FBIOS;FBAV/2]|FACEBOOK|Other",
        "Mozilla iPhone AppleWebKit GSA/330 Safari/604|GOOGLE_APP|Safari",
        "Mozilla iPhone AppleWebKit CriOS/140 Safari/604|BROWSER|Chrome",
        "Mozilla iPhone AppleWebKit FxiOS/140 Safari/604|BROWSER|Firefox",
        "Mozilla iPhone AppleWebKit EdgiOS/140 Safari/604|BROWSER|Edge",
        "Mozilla Android EdgA/140 Chrome/140 Safari/537|BROWSER|Edge",
        "Mozilla Android; wv) Chrome/140 Safari/537|ANDROID_WEBVIEW|Chrome",
        "Mozilla iPhone AppleWebKit Mobile/123|IOS_WEBVIEW|Other",
        "Mozilla Windows Chrome/140 Safari/537|BROWSER|Chrome",
        "Mozilla HeadlessChrome/140 Safari/537|AUTOMATION|Chrome",
        "Yeti/1.1|AUTOMATION|Other",
        "something unknown|UNKNOWN|Other"
    })
    void separatesContextFromBrowser(String ua,String context,String browser) {
        assertEquals(context,AnalyticsClientContext.context(ua));
        assertEquals(browser,AnalyticsClientContext.browser(ua));
    }
}
