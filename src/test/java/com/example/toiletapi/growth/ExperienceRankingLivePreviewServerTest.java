package com.example.toiletapi.growth;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExperienceRankingLivePreviewServerTest extends AdminGrowthRankingDatabaseTest {
    @Test void keyedBoundedHttpHostOnlyExposesRankingAndNeverMutatesOrExportsRawEmail() throws Exception {
        var client=HttpClient.newHttpClient();String secret="1".repeat(64);
        try(var running=ExperienceRankingLivePreviewServer.serve(new InetSocketAddress("127.0.0.1",0),secret,Instant.now().plusSeconds(30),service)) {
            String origin="http://127.0.0.1:"+running.server().getAddress().getPort();
            assertEquals(401,client.send(HttpRequest.newBuilder(URI.create(origin+"/__ready")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            var ready=client.send(request(origin+"/__ready",secret).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,ready.statusCode());
            assertEquals("private, no-store",ready.headers().firstValue("Cache-Control").orElseThrow());
            String path=origin+ExperienceRankingLivePreviewServer.PREFIX;
            var result=client.send(request(path+"/search",secret).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"keyword\":\"member2@example.test\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,result.statusCode());assertTrue(result.body().contains("me***@example.test"));assertFalse(result.body().contains("member2@example.test"));
            assertEquals(200,client.send(request(path+"/distribution",secret).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(405,client.send(request(path+"/search",secret).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404,client.send(request(origin+"/api/admin/v1/reviews",secret).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(413,client.send(request(path+"/search",secret).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(new byte[4097])).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(400,client.send(request(path+"/search?keyword=not-allowed",secret).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(7,service.distribution().totalMembers());
        }
    }
    @Test void expiredHttpHostDeniesEvenValidGatewayKey() throws Exception {
        String secret="1".repeat(64);var client=HttpClient.newHttpClient();
        try(var running=ExperienceRankingLivePreviewServer.serve(new InetSocketAddress("127.0.0.1",0),secret,Instant.now().minusSeconds(1),service)) {
            var response=client.send(request("http://127.0.0.1:"+running.server().getAddress().getPort()+"/__ready",secret).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(410,response.statusCode());
        }
    }
    private static HttpRequest.Builder request(String url,String secret) {return HttpRequest.newBuilder(URI.create(url)).header("X-Preview-Gateway",secret).timeout(Duration.ofSeconds(3));}
}
