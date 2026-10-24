package com.example.toiletapi.review;

import com.example.toiletapi.global.exception.ApiErrorResponse;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;

/** Test classpath only. Bounded read-only production snapshot; no operational connection or mutation endpoint.
 * The outer preview gateway checks the real administrator on every request. This loopback host additionally
 * requires the one-time gateway secret and uses the actual controller/access/service/repository classes.
 */
public final class WrittenReviewsPreviewServer {
    private static final long FIXTURE_ADMIN=Long.MAX_VALUE;
    static final ObjectMapper JSON=new ObjectMapper().registerModule(new SimpleModule().addSerializer(OffsetDateTime.class,
            new JsonSerializer<>() { @Override public void serialize(OffsetDateTime value,JsonGenerator output,SerializerProvider provider)
                    throws java.io.IOException { output.writeString(value.toString()); } }));
    public static void main(String[] args) throws Exception {
        String input=System.getenv("WRITTEN_REVIEWS_PREVIEW_SNAPSHOT"),secret=System.getenv("PREVIEW_GATEWAY_TOKEN");
        if(args.length!=0 || input==null || secret==null || !secret.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Bounded snapshot and gateway secret required");
        Path path=Path.of(input).toAbsolutePath().normalize();
        if(Files.size(path)>20*1024*1024)throw new IllegalArgumentException("Snapshot too large");
        var fixture=loadSnapshot(Files.readString(path));
        int port=Integer.parseInt(System.getenv().getOrDefault("WRITTEN_REVIEWS_PREVIEW_PORT","8214"));
        int ttl=Integer.parseInt(System.getenv().getOrDefault("WRITTEN_REVIEWS_PREVIEW_TTL_SECONDS","7200"));
        if(port<1024 || port>65535 || ttl<60 || ttl>7200)throw new IllegalArgumentException("Bounded loopback lifetime required");
        Instant expires=Instant.now().plusSeconds(ttl);
        var jwt=Jwt.withTokenValue("isolated-preview-admin").header("alg","HS256").subject(Long.toString(FIXTURE_ADMIN))
                .claim("auth_version",0).claim("roles",List.of("ADMIN")).build();
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),0);
        var threads=Executors.newFixedThreadPool(4);server.setExecutor(threads);
        server.createContext("/",exchange->{
            exchange.getResponseHeaders().set("Cache-Control","private, no-store");
            exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Vary","Cookie, Authorization, Origin");
            int status=200;Object body;
            try {
                String received=exchange.getRequestHeaders().getFirst("X-Preview-Gateway");
                if(received==null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.US_ASCII),received.getBytes(StandardCharsets.US_ASCII)))
                    throw new ReviewFailure(401,"PREVIEW_AUTHENTICATION_REQUIRED","프리뷰 인증이 필요합니다.");
                if(!expires.isAfter(Instant.now()))throw new ReviewFailure(410,"PREVIEW_EXPIRED","프리뷰 사용 시간이 종료되었습니다.");
                if(!"GET".equals(exchange.getRequestMethod()))throw new ReviewFailure(405,"METHOD_NOT_ALLOWED","조회만 가능합니다.");
                var uri=exchange.getRequestURI();String requestPath=uri.getPath();
                if(requestPath.equals("/api/admin/v1/reviews")) {
                    var query=query(uri.getRawQuery());
                    if(!Set.of("size","cursor").containsAll(query.keySet()))throw new IllegalArgumentException();
                    body=fixture.controller().page(jwt,query.get("cursor"),Integer.parseInt(query.getOrDefault("size","20")));
                } else if(requestPath.matches("/api/admin/v1/reviews/[0-9]+") && uri.getRawQuery()==null) {
                    body=fixture.controller().detail(jwt,Long.parseLong(requestPath.substring(requestPath.lastIndexOf('/')+1)));
                } else throw new ReviewFailure(404,"PREVIEW_PATH_DENIED","프리뷰 조회 경로를 확인해 주세요.");
            } catch(ReviewFailure failure) {status=failure.status();body=ApiErrorResponse.of(failure.code(),failure.getMessage());}
            catch(IllegalArgumentException invalid) {status=400;body=ApiErrorResponse.of("INVALID_REQUEST","조회 조건을 확인해 주세요.");}
            catch(Exception unavailable) {status=503;body=ApiErrorResponse.of("PREVIEW_UNAVAILABLE","프리뷰를 불러올 수 없습니다.");}
            byte[] bytes=JSON.writeValueAsBytes(body);exchange.sendResponseHeaders(status,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(0);threads.shutdownNow();}));
        Thread.ofPlatform().daemon(true).start(()->{
            try {Thread.sleep(Duration.ofSeconds(ttl));server.stop(0);threads.shutdownNow();}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        });
        System.out.println("WRITTEN_REVIEWS_PREVIEW_READY loopback=true readonly=true lifetimeSeconds="+ttl);
    }
    record Fixture(AdminReviewController controller,JdbcTemplate jdbc) { }
    static Fixture loadSnapshot(String payload) throws Exception {
        JsonNode snapshot=JSON.readTree(payload);
        JsonNode reviews=snapshot.path("reviews"),toilets=snapshot.path("toilets"),authors=snapshot.path("authors");
        if(!reviews.isArray() || reviews.size()>5000 || !toilets.isArray() || toilets.size()>5000
                || !authors.isArray() || authors.size()>5000)throw new IllegalArgumentException("Bounded snapshot arrays required");
        Instant captured=Instant.parse(snapshot.path("capturedAt").asText());
        if(captured.isAfter(Instant.now().plusSeconds(60)) || captured.isBefore(Instant.now().minusSeconds(7200)))
            throw new IllegalArgumentException("Fresh snapshot required");
        var source=new DriverManagerDataSource("jdbc:h2:mem:written_reviews_preview_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY,status VARCHAR(20),display_name VARCHAR(100),auth_version BIGINT)");
        jdbc.execute("CREATE TABLE user_role(user_id BIGINT,role VARCHAR(20))");
        jdbc.execute("CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY,name VARCHAR(255),visibility_status VARCHAR(24))");
        // H2 measures VARCHAR in UTF-16 units; the service/MySQL limit is 200 Unicode code points.
        jdbc.execute("CREATE TABLE toilet_review(review_id BIGINT PRIMARY KEY,toilet_id BIGINT,author_user_id BIGINT,author_detached BOOLEAN,satisfaction INT,cleanliness INT,paper_available BOOLEAN,wait_minutes INT,comment VARCHAR(400),created_at TIMESTAMP(6),updated_at TIMESTAMP(6))");
        jdbc.update("INSERT INTO app_user VALUES(?,'ACTIVE','프리뷰 인증 경계',0)",FIXTURE_ADMIN);
        jdbc.update("INSERT INTO user_role VALUES(?,'ADMIN')",FIXTURE_ADMIN);
        for(JsonNode toilet:toilets)jdbc.update("INSERT INTO toilet VALUES(?,?,?)",id(toilet.path("id")),toilet.path("name").asText(),toilet.path("visibilityStatus").asText());
        for(JsonNode author:authors) {
            long id=id(author.path("id"));String status=author.path("status").asText();
            if(id==FIXTURE_ADMIN || !Set.of("ACTIVE","PENDING_CONSENT","SUSPENDED","WITHDRAWN").contains(status))
                throw new IllegalArgumentException("Known account status required");
            String displayName="ACTIVE".equals(status) && !author.path("displayName").isNull()?author.path("displayName").asText():null;
            jdbc.update("INSERT INTO app_user VALUES(?,?,?,0)",id,status,displayName);
        }
        for(JsonNode review:reviews) {
            Long author=review.path("authorUserId").isNull()?null:id(review.path("authorUserId"));
            if(author!=null && author==FIXTURE_ADMIN)throw new IllegalArgumentException("Reserved author");
            if(review.path("authorDetached").asBoolean())author=null;
            int satisfaction=review.path("satisfaction").asInt(),cleanliness=review.path("cleanliness").asInt(),wait=review.path("waitMinutes").asInt();
            String comment=review.path("comment").asText();
            if(satisfaction<1 || satisfaction>5 || cleanliness<1 || cleanliness>5 || wait<0 || wait>60 || wait%10!=0 || comment.codePointCount(0,comment.length())>200)
                throw new IllegalArgumentException("Invalid review snapshot");
            jdbc.update("INSERT INTO toilet_review VALUES(?,?,?,?,?,?,?,?,?,?,?)",id(review.path("id")),id(review.path("toiletId")),author,
                    review.path("authorDetached").asBoolean(),satisfaction,cleanliness,review.path("paper").asBoolean(),wait,comment,
                    LocalDateTime.parse(review.path("createdAt").asText()),LocalDateTime.parse(review.path("updatedAt").asText()));
        }
        return new Fixture(new AdminReviewController(new AdminReviewAccess(jdbc),new AdminReviewService(new AdminReviewRepository(jdbc))),jdbc);
    }
    private static long id(JsonNode value) {
        long id=Long.parseLong(value.asText());if(id<=0)throw new IllegalArgumentException("Positive ID required");return id;
    }
    private static Map<String,String> query(String raw) {
        var values=new HashMap<String,String>();if(raw==null || raw.isEmpty())return values;
        if(raw.length()>500)throw new IllegalArgumentException();
        for(String part:raw.split("&",-1)) {
            String[] pair=part.split("=",2);String key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8);
            String value=pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"";
            if(values.putIfAbsent(key,value)!=null)throw new IllegalArgumentException();
        }
        return values;
    }
}
