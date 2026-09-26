package com.example.toiletapi.places;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PlaceSearchServiceTest {
    MockRestServiceServer upstream;
    PlaceSearchService service;
    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        upstream = MockRestServiceServer.bindTo(builder).build();
        service = new PlaceSearchService(builder.build(), new ObjectMapper(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }
    void respond(String kind, String docs) {
        upstream.expect(request -> assertTrue(request.getURI().getPath().endsWith(kind + ".json")))
                .andRespond(withSuccess("{\"documents\":" + docs + "}", MediaType.APPLICATION_JSON));
    }
    @Test void combinesPlacesAndAddressesFiltersInvalidCoordinatesAndDeduplicates() {
        respond("keyword", """
            [{"id":"1","place_name":"대전역","address_name":"대전 동구","x":"127.43","y":"36.33"},
             {"id":"1","place_name":"대전역","x":"127.43","y":"36.33"},
             {"id":"2","place_name":"오류","x":"127.43","y":"NaN"},
             {"id":"3","place_name":"외국","x":"13","y":"36"}]
            """);
        respond("address", "[{\"address_name\":\"대전 동구\",\"x\":\"127.44\",\"y\":\"36.34\"}]");
        var result = service.search("대전역");
        assertEquals("kakao", result.provider()); assertEquals(2, result.items().size());
        assertEquals("place:1", result.items().getFirst().id()); assertEquals(36.33, result.items().getFirst().latitude());
        assertEquals("address", result.items().getLast().kind()); upstream.verify();
    }
    @Test void addressInputPrioritizesAddressAndEscapesLiteralQuery() {
        upstream.expect(request -> {
            assertTrue(request.getURI().toASCIIString().contains("query=Road%2023%26x%3D1"));
            assertFalse(request.getURI().getRawQuery().contains("&x="));
        }).andRespond(withSuccess("{\"documents\":[{\"id\":\"1\",\"place_name\":\"상점\",\"x\":\"127\",\"y\":\"36\"}]}", MediaType.APPLICATION_JSON));
        respond("address", "[{\"address_name\":\"도로 23\",\"x\":\"127.1\",\"y\":\"36.1\"}]");
        assertEquals("address", service.search("Road 23&x=1").items().getFirst().kind()); upstream.verify();
    }
    @Test void invalidInputDoesNotContactProvider() {
        for (String input : new String[]{"", "a", "가".repeat(81), "대전\n역", "대전\u200b역"})
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.search(input)).getStatusCode().value());
        upstream.verify();
    }
    @Test void providerErrorsAreSanitizedAndConcurrencyPermitIsReleased() {
        for (int i = 0; i < 6; i++) upstream.expect(anything()).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("secret-provider-message"));
        for (int i = 0; i < 6; i++) {
            var error = assertThrows(ResponseStatusException.class, () -> service.search("대전역"));
            assertEquals(502, error.getStatusCode().value()); assertNull(error.getCause()); assertFalse(error.getMessage().contains("secret"));
        }
        upstream.verify();
    }
    @Test void limitsProviderRequestsWithoutKeepingSearchHistory() {
        upstream.expect(org.springframework.test.web.client.ExpectedCount.times(120), anything()).andRespond(withServerError());
        for (int i = 0; i < 120; i++) assertEquals(502, assertThrows(ResponseStatusException.class, () -> service.search("대전역")).getStatusCode().value());
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> service.search("대전역")).getStatusCode().value());
        upstream.verify();
    }
    @Test void malformedProviderPayloadFailsClosed() {
        upstream.expect(anything()).andRespond(withSuccess("{\"documents\":{}}", MediaType.APPLICATION_JSON));
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> service.search("대전역")).getStatusCode().value());
    }
}
