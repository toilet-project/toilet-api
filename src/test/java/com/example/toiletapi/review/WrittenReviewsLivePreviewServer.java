package com.example.toiletapi.review;

import com.example.toiletapi.global.exception.ApiErrorResponse;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Temporary live GET host. No Spring startup, Flyway, Redis, scheduler, data export or snapshots.
 * The gateway validates the real ADMIN every request. This process independently checks its secret
 * and only executes the actual repository's three parameterized SELECT shapes.
 */
public final class WrittenReviewsLivePreviewServer {
    static final ObjectMapper JSON=new ObjectMapper().registerModule(new SimpleModule().addSerializer(OffsetDateTime.class,
            new JsonSerializer<>() { @Override public void serialize(OffsetDateTime value,JsonGenerator output,SerializerProvider provider)
                    throws java.io.IOException { output.writeString(value.toString()); } }));
    public static void main(String[] args) {
        try {start(args,System.getenv());}
        catch(Exception unavailable) {System.err.println("Live read-only preview initialization unavailable; no connection details logged.");System.exit(1);}
    }
    static void start(String[] args,Map<String,String> env) throws Exception {
        String secret=env.get("PREVIEW_GATEWAY_TOKEN"),bind=env.getOrDefault("WRITTEN_REVIEWS_PREVIEW_BIND_ADDRESS","127.0.0.1");
        if(args.length!=0 || !"temporary-read-only-preview".equals(env.get("WRITTEN_REVIEWS_LIVE_ENABLED"))
                || secret==null || !secret.matches("[a-f0-9]{64}") || !Set.of("127.0.0.1","0.0.0.0").contains(bind))
            throw new IllegalArgumentException("Explicit live preview configuration required");
        int port=Integer.parseInt(env.getOrDefault("WRITTEN_REVIEWS_PREVIEW_PORT","8214"));
        Instant expires=Instant.parse(env.get("WRITTEN_REVIEWS_PREVIEW_EXPIRES_AT"));
        long remaining=Duration.between(Instant.now(),expires).toMillis();
        if(port<1024 || port>65535 || remaining<1000 || remaining>7200000)throw new IllegalArgumentException("Bounded live preview required");
        var source=ReadOnlyReviewPreviewDataSource.fromEnvironment(env);source.verifyConnection();
        var service=new AdminReviewService(new AdminReviewRepository(new JdbcTemplate(source)));
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getByName(bind),port),8);
        var threads=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(threads);
        server.createContext("/",exchange->{
            exchange.getResponseHeaders().set("Cache-Control","private, no-store");
            exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            int status=200;Object body;
            try {
                String received=exchange.getRequestHeaders().getFirst("X-Preview-Gateway");
                if(received==null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.US_ASCII),received.getBytes(StandardCharsets.US_ASCII)))
                    throw new ReviewFailure(401,"PREVIEW_AUTHENTICATION_REQUIRED","프리뷰 인증이 필요합니다.");
                if(!expires.isAfter(Instant.now()))throw new ReviewFailure(410,"PREVIEW_EXPIRED","프리뷰 사용 시간이 종료되었습니다.");
                if(!"GET".equals(exchange.getRequestMethod()))throw new ReviewFailure(405,"METHOD_NOT_ALLOWED","조회만 가능합니다.");
                var uri=exchange.getRequestURI();String path=uri.getPath();
                if(path.equals("/__ready") && uri.getRawQuery()==null)body=Map.of("ready",true,"live",true,"readOnly",true,"expiresAt",expires.toString());
                else if(path.equals("/api/admin/v1/reviews")) {
                    var query=query(uri.getRawQuery());
                    if(!Set.of("size","cursor").containsAll(query.keySet()))throw new IllegalArgumentException();
                    body=service.page(query.get("cursor"),Integer.parseInt(query.getOrDefault("size","20")));
                } else if(path.matches("/api/admin/v1/reviews/[1-9][0-9]{0,18}") && uri.getRawQuery()==null)
                    body=service.detail(Long.parseLong(path.substring(path.lastIndexOf('/')+1)));
                else throw new ReviewFailure(404,"PREVIEW_PATH_DENIED","프리뷰 조회 경로를 확인해 주세요.");
            } catch(ReviewFailure failure) {status=failure.status();body=ApiErrorResponse.of(failure.code(),failure.getMessage());}
            catch(IllegalArgumentException invalid) {status=400;body=ApiErrorResponse.of("INVALID_REQUEST","조회 조건을 확인해 주세요.");}
            catch(Exception unavailable) {status=503;body=ApiErrorResponse.of("PREVIEW_UNAVAILABLE","리뷰 조회를 일시적으로 불러올 수 없습니다.");}
            byte[] bytes=JSON.writeValueAsBytes(body);exchange.sendResponseHeaders(status,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });
        server.start();Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(0);threads.shutdownNow();}));
        Thread.ofPlatform().daemon(true).start(()->{
            try {Thread.sleep(Math.max(0,Duration.between(Instant.now(),expires).toMillis()));server.stop(0);threads.shutdownNow();}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        });
        System.out.println("WRITTEN_REVIEWS_LIVE_PREVIEW_READY live=true readonly=true noSnapshot=true");
    }
    static Map<String,String> query(String raw) {
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
