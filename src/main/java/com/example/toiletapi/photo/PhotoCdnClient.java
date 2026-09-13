package com.example.toiletapi.photo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class PhotoCdnClient {
    private final URI endpoint;
    private final String token;
    private final String publicOrigin;
    private final HttpClient client;
    private final ObjectMapper json=new ObjectMapper();

    PhotoCdnClient(PhotoCdnSettings settings,HttpClient client) {
        if(settings.zoneId()==null || !settings.zoneId().matches("[a-f0-9]{32}") || settings.token()==null
                || settings.token().length()<20 || !"https://api.geupddong.com".equals(settings.publicOrigin()))
            throw new IllegalArgumentException("Invalid profile photo CDN configuration");
        endpoint=URI.create("https://api.cloudflare.com/client/v4/zones/"+settings.zoneId()+"/purge_cache");
        token=settings.token();publicOrigin=settings.publicOrigin();this.client=client;
    }
    void purge(List<String> versions) throws Exception {
        if(versions.isEmpty() || versions.size()>30 || versions.stream().anyMatch(v->v==null || !v.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
            throw new IllegalArgumentException("Invalid photo versions");
        List<String> urls=versions.stream().map(v->publicOrigin+"/api/v1/profile-photos/"+v+".webp").toList();
        String body=json.writeValueAsString(Map.of("files",urls));
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()!=200) throw new PurgeException("HTTP_"+response.statusCode());
        if(response.body().length()>8192 || !json.readTree(response.body()).path("success").asBoolean(false))
            throw new PurgeException("INVALID_ACK");
    }
    void warm(String version) throws Exception {
        if(version==null || !version.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("Invalid photo version");
        URI url=URI.create(publicOrigin+"/api/v1/profile-photos/"+version+".webp");
        var request=HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(8)).GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());
        String type=response.headers().firstValue("Content-Type").orElse("");
        if(response.statusCode()!=200 || !type.toLowerCase(java.util.Locale.ROOT).startsWith("image/webp")
                || response.body().length==0 || response.body().length>100_000) throw new PurgeException("WARM_FAILED");
    }
    static class PurgeException extends Exception {PurgeException(String code){super(code);}}
}
