package com.example.toiletapi.quality.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DisplayGroupTranslatorTest {
    @Test
    void translatesKoreanGroupNamesInOneProviderRequest() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://translation.googleapis.com")
                .defaultHeader("X-Goog-Api-Key", "test-key");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var translator = new DisplayGroupTranslator(builder.build(), new ObjectMapper(), true);
        server.expect(requestTo("https://translation.googleapis.com/language/translate/v2"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Goog-Api-Key", "test-key"))
                .andRespond(withSuccess("""
                        {"data":{"translations":[
                          {"translatedText":"XXX Cultural Center"},
                          {"translatedText":"Noeun Station &amp; Plaza"}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        assertEquals(List.of("XXX Cultural Center", "Noeun Station & Plaza"),
                translator.translate(List.of("XXX문화원", "노은역 광장")));
        server.verify();
    }

    @Test
    void disabledTranslationFailsBeforeCallingProvider() {
        var translator = new DisplayGroupTranslator(RestClient.create(), new ObjectMapper(), false);
        assertThrows(DisplayGroupTranslationException.class, () -> translator.translate("XXX문화원"));
    }
}
