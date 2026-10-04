package com.example.toiletapi.review;

import com.example.toiletapi.auth.support.NativeMySqlFixture;
import com.example.toiletapi.growth.GrowthController;
import com.example.toiletapi.growth.GrowthBoundaryFilter;
import com.example.toiletapi.growth.GrowthExceptionHandler;
import com.example.toiletapi.growth.GrowthService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

/** Browser verification host on the test classpath only; never included in the production bootJar.
 * Reuses the guarded synthetic MySQL/JWT/real review HTTP fixture, then applies actual V40 growth SQL.
 * No credentials, live database, external ledger, or real user record is read or changed.
 */
public final class GrowthHttpFixture {
    private GrowthHttpFixture() { }

    public static void main(String[] args) throws Exception {
        if(args.length!=1 || !NativeMySqlFixture.enabled())
            throw new IllegalArgumentException("Fixture marker and output path required");
        Path output=Path.of(args[0]).toAbsolutePath().normalize();
        String marker=System.getenv("ACCOUNT_RETENTION_MYSQL_MARKER");
        if(output.getParent()==null || !output.getParent().getFileName().toString().equals("account-retention-mysql-"+marker))
            throw new IllegalArgumentException("Metadata must stay in this fixture directory");
        int minutes=Integer.parseInt(System.getenv().getOrDefault("GROWTH_FIXTURE_MINUTES","45"));
        if(minutes<1 || minutes>120)throw new IllegalArgumentException("Bounded fixture lifetime required");
        var application=new SpringApplication(Config.class);
        var context=application.run("--spring.config.location=optional:classpath:growth-http-fixture-only.properties",
                "--server.address=127.0.0.1","--server.port=0","--spring.flyway.enabled=false",
                "--spring.data.redis.repositories.enabled=false","--spring.jpa.open-in-view=false",
                "--reviews.enabled=true","--growth.enabled=true","--engagement.enabled=true",
                "--engagement.secret=isolated-engagement-fixture-secret-32-characters",
                "--engagement.allowed-origins=https://preview.geupddong.com","--logging.level.root=WARN");
        try {
            var growth=context.getBean(GrowthService.class);
            growth.initializePolicy(true);
            growth.backfillUser(1,true);
            var encoder=context.getBean(JwtEncoder.class);
            var tokens=new LinkedHashMap<String,String>();
            for(int id=1;id<=7;id++) {
                var claims=JwtClaimsSet.builder().subject(Integer.toString(id)).issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(minutes*60L)).claim("auth_version",0)
                        .claim("roles",List.of("USER")).build();
                tokens.put(Integer.toString(id),encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue());
            }
            int port=((WebServerApplicationContext)context).getWebServer().getPort();
            var metadata=Map.of("port",port,"tokens",tokens,"marker",marker,"syntheticOnly",true,
                    "jdbcUrl",((org.springframework.jdbc.datasource.DriverManagerDataSource)context.getBean(DataSource.class)).getUrl());
            Files.writeString(output,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata),StandardOpenOption.CREATE_NEW);
            Thread.ofPlatform().daemon(true).start(()->{
                try {Thread.sleep(Duration.ofMinutes(minutes));context.close();}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            });
            System.out.println("GROWTH_HTTP_FIXTURE_READY loopback=true syntheticOnly=true");
        } catch(Exception failure) {context.close();throw failure;}
    }

    @org.springframework.boot.test.context.TestConfiguration
    @Import({GrowthService.class,GrowthController.class,GrowthExceptionHandler.class,GrowthBoundaryFilter.class})
    static class Config extends ReviewHttpFixture.Config {
        @Override @Bean DataSource dataSource() {
            DataSource dataSource=super.dataSource();
            var jdbc=new JdbcTemplate(dataSource);
            // All rows in this database were created by NativeMySqlFixture in this invocation.
            jdbc.update("DELETE FROM toilet_review_submission");
            jdbc.update("DELETE FROM toilet_review");
            jdbc.execute("CREATE TABLE region_sigungu_reference(sigungu_code CHAR(5) PRIMARY KEY,sido_code CHAR(2),sido_name VARCHAR(50),sigungu_name VARCHAR(100),display_name VARCHAR(160),is_active BOOLEAN)");
            jdbc.execute("CREATE TABLE current_toilet_region(toilet_id BIGINT PRIMARY KEY,sigungu_code CHAR(5),status VARCHAR(30))");
            jdbc.update("INSERT INTO region_sigungu_reference VALUES('30110','30','대전','동구','동구',TRUE),('30140','30','대전','중구','중구',TRUE)");
            jdbc.update("INSERT INTO current_toilet_region SELECT toilet_id,CASE WHEN toilet_id<=15 THEN '30110' ELSE '30140' END,'VERIFIED' FROM toilet");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V40__member_growth.sql")).execute(dataSource);
            jdbc.update("UPDATE app_user SET display_name='가상 성장 회원' WHERE user_id=1");
            var created=LocalDateTime.ofInstant(Instant.now().minusSeconds(2*86400),ZoneOffset.ofHours(9));
            for(long id=1;id<=30;id++) {
                String reviewKey=UUID.randomUUID().toString();
                jdbc.update("""
                        INSERT INTO toilet_review(review_key,toilet_id,author_user_id,satisfaction,cleanliness,paper_available,
                            wait_minutes,comment,created_at,updated_at)
                        VALUES(?,?,1,4,5,TRUE,0,'성장 페이지 검증용 가상 리뷰',?,?)
                        """,reviewKey,id,created.plusMinutes(id),created.plusMinutes(id));
                long reviewId=jdbc.queryForObject("SELECT review_id FROM toilet_review WHERE review_key=?",Long.class,reviewKey);
                jdbc.update("INSERT INTO toilet_review_submission(user_id,request_key,content_hash,review_id) VALUES(1,?,?,?)",
                        UUID.randomUUID().toString(),"a".repeat(64),reviewId);
            }
            return dataSource;
        }
    }
}
