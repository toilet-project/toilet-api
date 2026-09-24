package com.example.toiletapi.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class AnalyticsEventServiceTest {

    private static final String SECRET = "12345678901234567890123456789012";

    @Test
    void storesOnlyAllowlistedAndCoarsenedValues() {
        AnalyticsRepository repository = mock(AnalyticsRepository.class);
        AnalyticsEventService service = new AnalyticsEventService(repository,
                Clock.fixed(Instant.parse("2026-09-16T01:02:03Z"), ZoneOffset.UTC), true, SECRET);
        HttpServletRequest http = request("https://geupddong.com", "203.0.113.91",
                "Mozilla/5.0 (Linux; Android 15) AppleWebKit Chrome/140 Mobile Safari/537.36",
                "https://geupddong.com/toilet/1111", "KR");

        service.collect(new AnalyticsEventRequest("toilet_detail_open", "/toilet/1111?token=secret",
                "map", "26+", 999, null, "session-test-1234", "map", true, true,
                "www.google.com", null, null), http);

        ArgumentCaptor<AnalyticsRepository.EventRow> row = ArgumentCaptor.forClass(AnalyticsRepository.EventRow.class);
        verify(repository).insert(row.capture());
        assertEquals("/toilet/:id", row.getValue().pageKey());
        assertEquals("Organic Search", row.getValue().channel());
        assertEquals("google", row.getValue().source());
        assertEquals("mobile", row.getValue().device());
        assertEquals("Android", row.getValue().os());
        assertEquals("Chrome", row.getValue().browser());
        assertEquals(32, row.getValue().visitorHash().length);
        assertEquals(32, row.getValue().sessionHash().length);
        assertEquals(0, row.getValue().engagementSeconds());
        assertEquals("map", row.getValue().eventDetail());
        assertEquals(true, row.getValue().newVisitor());
        assertEquals("UNFLAGGED", row.getValue().trafficClass());
    }

    @Test
    void rejectsUnknownOriginsAndEvents() {
        AnalyticsRepository repository = mock(AnalyticsRepository.class);
        AnalyticsEventService service = new AnalyticsEventService(repository, Clock.systemUTC(), true, SECRET);
        HttpServletRequest badOrigin = request("https://attacker.invalid", "203.0.113.1", "Mozilla/5.0", "", "KR");
        assertThrows(ResponseStatusException.class, () -> service.collect(
                new AnalyticsEventRequest("page_view", "/", null, null, null, null, null, null, null, null,
                        null, null, null), badOrigin));

        HttpServletRequest valid = request("https://geupddong.com", "203.0.113.1", "Mozilla/5.0", "", "KR");
        assertThrows(ResponseStatusException.class, () -> service.collect(
                new AnalyticsEventRequest("raw_search_query", "/", null, null, null, null, null, null, null, null,
                        null, null, null), valid));
        verify(repository, never()).insert(any());
    }

    @Test
    void disabledCollectionDoesNotWrite() {
        AnalyticsRepository repository = mock(AnalyticsRepository.class);
        AnalyticsEventService service = new AnalyticsEventService(repository, Clock.systemUTC(), false, SECRET);
        service.collect(new AnalyticsEventRequest("page_view", "/", null, null, null, null, null, null, null, null,
                        null, null, null),
                request("https://geupddong.com", "203.0.113.1", "Mozilla/5.0", "", "KR"));
        verify(repository, never()).insert(any());
    }

    @Test
    void usesFirstTouchUtmAndCanonicalPageKeys() {
        AnalyticsRepository repository = mock(AnalyticsRepository.class);
        AnalyticsEventService service = new AnalyticsEventService(repository,
                Clock.fixed(Instant.parse("2026-09-16T01:02:03Z"), ZoneOffset.UTC), true, SECRET);

        service.collect(new AnalyticsEventRequest("screen_view", "/toilet/:id", null, null, null, null,
                "session-test-1234", "review_list", null, false, "search.naver.com", "kakao", "social"),
                request("https://www.geupddong.com", "203.0.113.1", "Mozilla/5.0 (iPhone) Safari/537.36",
                        "https://www.geupddong.com/toilet/53585", "KR"));

        ArgumentCaptor<AnalyticsRepository.EventRow> row = ArgumentCaptor.forClass(AnalyticsRepository.EventRow.class);
        verify(repository).insert(row.capture());
        assertEquals("/toilet/:id", row.getValue().pageKey());
        assertEquals("Organic Social", row.getValue().channel());
        assertEquals("kakao", row.getValue().source());
        assertEquals("review_list", row.getValue().eventDetail());
    }

    @Test
    void dropsPreviewEventsWithoutFailingTheRequest() {
        AnalyticsRepository repository = mock(AnalyticsRepository.class);
        AnalyticsEventService service = new AnalyticsEventService(repository, Clock.systemUTC(), true, SECRET);

        service.collect(new AnalyticsEventRequest("page_view", "/", null, null, null, null, null, null,
                        null, null, "www.google.com", null, null),
                request("https://preview.geupddong.com", "203.0.113.1", "Mozilla/5.0", "", "KR"));

        verify(repository, never()).insert(any());
    }

    @Test
    void classifiesDirectSearchExternalInternalAndInvalidReferralsFromTheInitialHost() {
        AnalyticsRepository repository = mock(AnalyticsRepository.class);
        AnalyticsEventService service = new AnalyticsEventService(repository,
                Clock.fixed(Instant.parse("2026-09-16T01:02:03Z"), ZoneOffset.UTC), true, SECRET);
        HttpServletRequest http = request("https://geupddong.com", "203.0.113.1",
                "Mozilla/5.0 (iPhone) Safari/537.36", "https://geupddong.com/", "KR");

        service.collect(eventWithReferrer(null), http);
        service.collect(eventWithReferrer("m.search.naver.com"), http);
        service.collect(eventWithReferrer("example.org"), http);
        service.collect(eventWithReferrer("www.geupddong.com"), http);
        service.collect(eventWithReferrer("not a host/path"), http);

        ArgumentCaptor<AnalyticsRepository.EventRow> rows = ArgumentCaptor.forClass(AnalyticsRepository.EventRow.class);
        verify(repository, times(5)).insert(rows.capture());
        assertEquals("Direct", rows.getAllValues().get(0).channel());
        assertEquals("none", rows.getAllValues().get(0).source());
        assertEquals("Organic Search", rows.getAllValues().get(1).channel());
        assertEquals("naver", rows.getAllValues().get(1).source());
        assertEquals("Referral", rows.getAllValues().get(2).channel());
        assertEquals("example.org", rows.getAllValues().get(2).source());
        assertEquals("Internal", rows.getAllValues().get(3).channel());
        assertEquals("geupddong", rows.getAllValues().get(3).source());
        assertEquals("Unassigned", rows.getAllValues().get(4).channel());
        assertEquals("unknown", rows.getAllValues().get(4).source());
    }

    @Test
    void keepsKnownPolicyPagesAndCollapsesUnknownPaths() {
        AnalyticsRepository repository = mock(AnalyticsRepository.class);
        AnalyticsEventService service = new AnalyticsEventService(repository,
                Clock.fixed(Instant.parse("2026-09-16T01:02:03Z"), ZoneOffset.UTC), true, SECRET);
        HttpServletRequest http = request("https://geupddong.com", "203.0.113.1",
                "Mozilla/5.0 (iPhone) Safari/537.36", "", "KR");

        service.collect(event("/policies/privacy/?from=private"), http);
        service.collect(event("/profile?member=private"), http);

        ArgumentCaptor<AnalyticsRepository.EventRow> rows = ArgumentCaptor.forClass(AnalyticsRepository.EventRow.class);
        verify(repository, times(2)).insert(rows.capture());
        assertEquals("/policies/privacy", rows.getAllValues().get(0).pageKey());
        assertEquals("/other", rows.getAllValues().get(1).pageKey());
    }

    @Test
    void groupsPublicRegionAndAccountRoutesWithoutSavingSlugsOrQueryValues() {
        AnalyticsRepository repository = mock(AnalyticsRepository.class);
        AnalyticsEventService service = new AnalyticsEventService(repository,
                Clock.fixed(Instant.parse("2026-09-16T01:02:03Z"), ZoneOffset.UTC), true, SECRET);
        HttpServletRequest http = request("https://geupddong.com", "203.0.113.1",
                "Mozilla/5.0 (iPhone) Safari/537.36", "", "KR");

        service.collect(event("/en/regions/seoul/gangnam-gu"), http);
        service.collect(event("/ja/regions/seoul/gangnam-gu/toilet/123-public?private=x"), http);
        service.collect(event("/zh-cn/account"), http);

        ArgumentCaptor<AnalyticsRepository.EventRow> rows = ArgumentCaptor.forClass(AnalyticsRepository.EventRow.class);
        verify(repository, times(3)).insert(rows.capture());
        assertEquals("/regions", rows.getAllValues().get(0).pageKey());
        assertEquals("/regions/:sido/:district/toilet/:id", rows.getAllValues().get(1).pageKey());
        assertEquals("/account", rows.getAllValues().get(2).pageKey());
    }

    private static AnalyticsEventRequest eventWithReferrer(String host) {
        return event("/", host);
    }

    @Test
    void excludesNamedCrawlersWithoutDroppingHumanSearchReferrals() {
        AnalyticsRepository repository = mock(AnalyticsRepository.class);
        AnalyticsEventService service = new AnalyticsEventService(repository, Clock.systemUTC(), true, SECRET);
        for (String ua : new String[]{"Googlebot/2.1", "bingbot/2.0",
                "Mozilla/5.0 (compatible; Yeti/1.1; +https://naver.me/spd) Chrome/123 Safari/537.36",
                "Ads-Naver/1.0", "Blueno/1.0", "Claude-User/1.0", "ChatGPT-User/1.0", "facebookexternalhit/1.1"}) {
            service.collect(eventWithReferrer("search.naver.com"),
                    request("https://geupddong.com", "203.0.113.1", ua, "", "KR"));
        }
        verify(repository, never()).insert(any());
        service.collect(eventWithReferrer("search.naver.com"),
                request("https://geupddong.com", "203.0.113.1", "Mozilla/5.0 (iPhone) Safari/537.36", "", "KR"));
        verify(repository).insert(any());
    }

    @Test
    void optInRetainsBotFlagWithoutMixingHumanSessionsOrRetainingRawIdentity() {
        AnalyticsRepository repository=mock(AnalyticsRepository.class);
        AnalyticsEventService service=new AnalyticsEventService(new AnalyticsEventWriter(repository),
                Clock.fixed(Instant.parse("2026-09-24T01:00:00Z"),ZoneOffset.UTC),true,SECRET,true);
        service.collect(eventWithReferrer(null),request("https://geupddong.com","203.0.113.1","Yeti/1.1","","KR"));
        service.collect(eventWithReferrer(null),request("https://geupddong.com","203.0.113.1","Mozilla/5.0 Safari/537.36","","KR"));
        service.collect(eventWithReferrer(null),request("https://geupddong.com","203.0.113.1","","","KR"));
        service.collect(eventWithReferrer(null),request("https://preview.geupddong.com","203.0.113.1","Yeti/1.1","","KR"));
        ArgumentCaptor<AnalyticsRepository.EventRow> rows=ArgumentCaptor.forClass(AnalyticsRepository.EventRow.class);
        verify(repository,times(2)).insert(rows.capture());
        assertEquals("BOT",rows.getAllValues().get(0).trafficClass());
        assertEquals("UNFLAGGED",rows.getAllValues().get(1).trafficClass());
        org.junit.jupiter.api.Assertions.assertFalse(java.util.Arrays.equals(rows.getAllValues().get(0).sessionHash(),rows.getAllValues().get(1).sessionHash()));
    }

    private static AnalyticsEventRequest event(String path) {
        return event(path, null);
    }

    private static AnalyticsEventRequest event(String path, String host) {
        return new AnalyticsEventRequest("page_view", path, null, null, null, null,
                "session-test-1234", null, null, null, host, null, null);
    }

    private static HttpServletRequest request(String origin, String ip, String ua, String referer, String country) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Origin")).thenReturn(origin);
        when(request.getHeader("CF-Connecting-IP")).thenReturn(ip);
        when(request.getHeader("User-Agent")).thenReturn(ua);
        when(request.getHeader("Referer")).thenReturn(referer);
        when(request.getHeader("CF-IPCountry")).thenReturn(country);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        return request;
    }
}
