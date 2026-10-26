package com.example.toiletapi.growth;

import com.example.toiletapi.auth.privacy.EmailProtection;
import com.example.toiletapi.global.exception.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Opt-in temporary live reporting only. No Spring/Flyway/Redis/scheduler or data copies. */
public final class ExperienceRankingLivePreviewServer {
    private static String startupStage="PACKAGE";
    private static final ObjectMapper JSON=new ObjectMapper();
    static final String PREFIX="/api/admin/v1/growth/ranking";
    public static void main(String[] args) {
        try {start(args,System.getenv());}
        catch(Exception unavailable) {
            String reason=unavailable instanceof ReadOnlyGrowthRankingPreviewDataSource.InitializationFailure failure
                    ?failure.stage().name()+"_"+failure.reason().name():startupStage;
            System.err.println("EXPERIENCE_RANKING_LIVE_FAILURE:"+reason);System.exit(1);
        } catch(LinkageError unavailable) {System.err.println("EXPERIENCE_RANKING_LIVE_FAILURE:CLASSPATH");System.exit(1);}
        catch(OutOfMemoryError unavailable) {System.err.println("EXPERIENCE_RANKING_LIVE_FAILURE:MEMORY");System.exit(1);}
    }
    static void start(String[] args,Map<String,String> env) throws Exception {
        mark("CONFIGURATION");
        String secret=env.get("PREVIEW_GATEWAY_TOKEN"),bind=env.getOrDefault("EXPERIENCE_RANKING_PREVIEW_BIND_ADDRESS","127.0.0.1");
        if(args.length!=0 || !"temporary-read-only-preview".equals(env.get("EXPERIENCE_RANKING_LIVE_ENABLED"))
                || secret==null || !secret.matches("[a-f0-9]{64}") || !Set.of("127.0.0.1","0.0.0.0").contains(bind))throw new IllegalArgumentException();
        int port=Integer.parseInt(env.getOrDefault("EXPERIENCE_RANKING_PREVIEW_PORT","8216"));
        Instant expires=Instant.parse(env.get("EXPERIENCE_RANKING_PREVIEW_EXPIRES_AT"));
        long remaining=Duration.between(Instant.now(),expires).toMillis();
        if(port<1024 || port>65535 || remaining<1000 || remaining>7200000)throw new IllegalArgumentException();
        String searchKey=env.get("AUTH_EMAIL_SEARCH_KEY");
        if(searchKey==null || Base64.getDecoder().decode(searchKey).length!=32)throw new IllegalArgumentException();
        // Search uses the same keyed hash, but never imports any email decryption key.
        var properties=new StandardEnvironment();properties.getPropertySources().addFirst(new MapPropertySource("preview-email-search",
                Map.of("AUTH_EMAIL_ENCRYPTION_ENABLED","false","AUTH_EMAIL_SEARCH_KEY",searchKey)));
        var email=new EmailProtection(properties);
        mark("DATASOURCE_CONFIGURATION");var source=ReadOnlyGrowthRankingPreviewDataSource.fromEnvironment(env);
        source.verifyConnection(stage->mark(stage.name()));
        var service=new AdminGrowthRankingService(new AdminGrowthRankingRepository(new JdbcTemplate(source)),email);
        mark("HTTP_BIND");var running=serve(new InetSocketAddress(InetAddress.getByName(bind),port),secret,expires,service);
        Runtime.getRuntime().addShutdownHook(new Thread(running::close));
        Thread.ofPlatform().daemon(true).start(()->{
            try {Thread.sleep(Math.max(0,Duration.between(Instant.now(),expires).toMillis()));running.close();}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        });
        System.out.println("EXPERIENCE_RANKING_LIVE_PREVIEW_READY live=true readonly=true noSnapshot=true");
    }
    static Running serve(InetSocketAddress address,String secret,Instant expires,AdminGrowthRankingService service) throws Exception {
        var server=HttpServer.create(address,8);
        var threads=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(threads);server.createContext("/",exchange->{
            exchange.getResponseHeaders().set("Cache-Control","private, no-store");
            exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            int status=200;Object body;
            try {
                String received=exchange.getRequestHeaders().getFirst("X-Preview-Gateway");
                if(received==null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.US_ASCII),received.getBytes(StandardCharsets.US_ASCII)))throw new Failure(401,"PREVIEW_AUTHENTICATION_REQUIRED");
                if(!expires.isAfter(Instant.now()))throw new Failure(410,"PREVIEW_EXPIRED");
                var uri=exchange.getRequestURI();String path=uri.getRawPath(),method=exchange.getRequestMethod();
                if(uri.getRawQuery()!=null)throw new IllegalArgumentException();
                if(path.equals("/__ready") && method.equals("GET"))body=Map.of("ready",true,"live",true,"readOnly",true,"expiresAt",expires.toString());
                else if(path.equals(PREFIX+"/distribution") && method.equals("GET"))body=service.distribution();
                else if(path.equals(PREFIX+"/search") && method.equals("POST")) {
                    String type=exchange.getRequestHeaders().getFirst("Content-Type");
                    if(type==null || !type.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;.*)?"))throw new Failure(415,"PREVIEW_CONTENT_TYPE_DENIED");
                    byte[] bytes=exchange.getRequestBody().readNBytes(4097);if(bytes.length>4096)throw new Failure(413,"PREVIEW_REQUEST_TOO_LARGE");
                    body=service.search(AdminGrowthRankingModels.parse(bytes));
                } else if(Set.of("/__ready",PREFIX+"/search",PREFIX+"/distribution").contains(path))throw new Failure(405,"METHOD_NOT_ALLOWED");
                else throw new Failure(404,"PREVIEW_PATH_DENIED");
            } catch(Failure failure) {status=failure.status;body=ApiErrorResponse.of(failure.code,"프리뷰 조회 요청을 확인해 주세요.");}
            catch(IllegalArgumentException invalid) {status=400;body=ApiErrorResponse.of("XP_RANKING_INVALID_REQUEST","경험치 조회 조건을 확인해 주세요.");}
            catch(Exception unavailable) {status=503;body=ApiErrorResponse.of("PREVIEW_UNAVAILABLE","경험치 조회를 일시적으로 불러올 수 없습니다.");}
            byte[] bytes=JSON.writeValueAsBytes(body);exchange.sendResponseHeaders(status,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}finally{exchange.close();}
        });
        server.start();return new Running(server,threads);
    }
    record Running(HttpServer server,ThreadPoolExecutor threads) implements AutoCloseable {
        @Override public void close() {server.stop(0);threads.shutdownNow();}
    }
    private static final class Failure extends RuntimeException {
        final int status;final String code;Failure(int status,String code) {this.status=status;this.code=code;}
    }
    private static void mark(String stage) {startupStage=stage;System.out.println("EXPERIENCE_RANKING_LIVE_STAGE:"+stage);}
}
