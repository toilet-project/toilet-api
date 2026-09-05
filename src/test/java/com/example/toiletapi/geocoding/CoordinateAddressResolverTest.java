package com.example.toiletapi.geocoding;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CoordinateAddressResolverTest {
    private MockRestServiceServer server;
    private CoordinateAddressResolver resolver;
    private static final BigDecimal LAT = new BigDecimal("36.350000049");
    private static final BigDecimal LNG = new BigDecimal("127.38000004");

    @BeforeEach void setUp() {
        var builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        server = MockRestServiceServer.bindTo(builder).build();
        resolver = new CoordinateAddressResolver(builder.build(), new ObjectMapper());
    }

    private void respond(String document) {
        server.expect(requestTo("https://dapi.kakao.com/v2/local/geo/coord2address.json?x=127.3800000&y=36.3500000&input_coord=WGS84"))
                .andRespond(withSuccess("{\"documents\":" + document + "}", MediaType.APPLICATION_JSON));
    }

    @Test void keepsBothTypedAddressesAndNormalizesStoredPrecision() {
        respond("[{\"road_address\":{\"address_name\":\"도로명 주소\"},\"address\":{\"address_name\":\"지번 주소\"}}]");
        var result = resolver.resolve(LAT, LNG);
        assertEquals("도로명 주소", result.roadAddress());
        assertEquals("지번 주소", result.jibunAddress());
        assertEquals(new BigDecimal("36.3500000"), result.latitude());
        assertEquals(new BigDecimal("127.3800000"), result.longitude());
        server.verify();
    }

    @Test void jibunOnlyDoesNotMasqueradeAsRoad() {
        respond("[{\"road_address\":null,\"address\":{\"address_name\":\"대전 서구 가장동 406\"}}]");
        var result = resolver.resolve(LAT, LNG);
        assertNull(result.roadAddress());
        assertEquals("대전 서구 가장동 406", result.jibunAddress());
    }

    @Test void roadOnlyIsAllowed() {
        respond("[{\"road_address\":{\"address_name\":\"도로명 주소\"},\"address\":null}]");
        var result = resolver.resolve(LAT, LNG);
        assertEquals("도로명 주소", result.roadAddress());
        assertNull(result.jibunAddress());
    }

    @Test void noResultsFails() { respond("[]"); assertThrows(AddressLookupException.class, () -> resolver.resolve(LAT, LNG)); }
    @Test void bothMissingFails() { respond("[{}]"); assertThrows(AddressLookupException.class, () -> resolver.resolve(LAT, LNG)); }
    @Test void blankAddressesFail() { respond("[{\"address\":{\"address_name\":\"  \"}}]"); assertThrows(AddressLookupException.class, () -> resolver.resolve(LAT, LNG)); }
    @Test void multipleResultsFail() { respond("[{},{}]"); assertThrows(AddressLookupException.class, () -> resolver.resolve(LAT, LNG)); }
    @Test void tooLongAddressFailsWithoutTruncating() {
        respond("[{\"address\":{\"address_name\":\"" + "가".repeat(256) + "\"}}]");
        assertThrows(AddressLookupException.class, () -> resolver.resolve(LAT, LNG));
    }
    @Test void providerErrorIsSanitized() {
        server.expect(anything()).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).body("sensitive-provider-message"));
        var error = assertThrows(AddressLookupException.class, () -> resolver.resolve(LAT, LNG));
        assertFalse(error.getMessage().contains("sensitive"));
        assertNull(error.getCause());
    }
    @Test void malformedBodyFails() {
        server.expect(anything()).andRespond(withSuccess("invalid-json", MediaType.APPLICATION_JSON));
        assertThrows(AddressLookupException.class, () -> resolver.resolve(LAT, LNG));
    }
    @Test void networkFailureIsSanitizedAndNotAutomaticallyRetried() {
        server.expect(anything()).andRespond(withException(new java.net.SocketTimeoutException("provider-timeout")));
        assertThrows(AddressLookupException.class, () -> resolver.resolve(LAT, LNG));
        server.verify();
    }
    @Test void invalidCoordinatesDoNotCallProvider() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(new BigDecimal("91"), LNG));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(LAT, null));
        server.verify();
    }
}
