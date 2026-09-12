package com.example.toiletapi.review;

import com.example.toiletapi.auth.config.*;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.auth.support.NativeMySqlFixture;
import com.example.toiletapi.global.config.CorsConfig;
import com.example.toiletapi.policy.service.PolicyConsentService;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.bind.annotation.*;

/** Test-classpath-only HTTP host. Never packaged in bootJar. No production configuration is loaded.
 * Real review HTTP/service/SQL/transactions/JWT/account/consent code; only OAuth issuance and map metadata
 * are fixtures. NativeMySqlFixture checks loopback port, isolated datadir, and per-run marker before DDL.
 */
public class ReviewHttpFixture {
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || !NativeMySqlFixture.enabled())throw new IllegalArgumentException("Fixture marker and output path required");
        Path output=Path.of(args[0]).toAbsolutePath().normalize();
        if(!output.getParent().getFileName().toString().equals("account-retention-mysql-"+System.getenv("ACCOUNT_RETENTION_MYSQL_MARKER")))
            throw new IllegalArgumentException("Metadata must stay in this fixture directory");
        var app=new SpringApplication(Config.class);
        var ctx=app.run("--spring.config.location=optional:classpath:review-http-fixture-only.properties",
                "--server.address=127.0.0.1","--server.port=0","--spring.flyway.enabled=false",
                "--spring.data.redis.repositories.enabled=false","--spring.jpa.open-in-view=false",
                "--reviews.enabled=true","--logging.level.root=WARN");
        int port=((WebServerApplicationContext)ctx).getWebServer().getPort();
        var encoder=ctx.getBean(JwtEncoder.class);
        int minutes=Integer.parseInt(System.getenv().getOrDefault("REVIEW_FIXTURE_MINUTES","20"));
        if(minutes<1 || minutes>120){ctx.close();throw new IllegalArgumentException("Bounded fixture lifetime required");}
        var tokens=new LinkedHashMap<String,String>();
        for(int id=1;id<=7;id++) {
            var claims=JwtClaimsSet.builder().subject(Integer.toString(id)).issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(minutes*60L)).claim("auth_version",0).claim("roles",List.of("USER")).build();
            tokens.put(Integer.toString(id),encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue());
        }
        var metadata=Map.of("port",port,"tokens",tokens,"marker",System.getenv("ACCOUNT_RETENTION_MYSQL_MARKER"));
        Files.writeString(output,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata),StandardOpenOption.CREATE_NEW);
        Thread.ofPlatform().daemon(true).start(()->{
            try{Thread.sleep(Duration.ofMinutes(minutes));ctx.close();}catch(InterruptedException e){Thread.currentThread().interrupt();}
        });
        System.out.println("REVIEW_HTTP_FIXTURE_READY loopback=true syntheticOnly=true");
    }

    @org.springframework.boot.test.context.TestConfiguration @EnableAutoConfiguration @EnableTransactionManagement
    @EnableJpaRepositories(basePackages={"com.example.toiletapi.auth.repository","com.example.toiletapi.policy.repository"})
    @Import({ReviewConfiguration.class,ReviewRepository.class,ReviewService.class,ReviewController.class,
            ReviewExceptionHandler.class,ReviewBoundaryFilter.class,SecurityConfig.class,JwtConfig.class,
            CorsConfig.class,com.example.toiletapi.global.exception.GlobalExceptionHandler.class,PolicyConsentService.class,FixtureReads.class})
    static class Config {
        @Bean DataSource dataSource() {
            var ds=NativeMySqlFixture.create(); var jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY,name VARCHAR(100),latitude DECIMAL(10,7),longitude DECIMAL(10,7))");
            for(String file:new String[]{"V1__create_auth_data_model.sql","V2__create_toilet_report_and_coordinate_revision.sql",
                    "V4__create_user_notification.sql","V5__create_coordinate_quality_review.sql","V7__create_policy_consent_model.sql",
                    "V11__account_withdrawal_retention.sql","V12__create_location_reviews.sql"})
                new ResourceDatabasePopulator(new ClassPathResource("db/migration/"+file)).execute(ds);
            for(int id=1;id<=7;id++) {
                jdbc.update("INSERT INTO app_user(user_id,status,display_name) VALUES(?,'ACTIVE',?)",id,"가상 검증 사용자 "+id);
                if(id!=7)jdbc.update("INSERT INTO user_policy_consent(user_id,policy_document_id,consent_source) SELECT ?,policy_document_id,'WEB_OAUTH_ONBOARDING' FROM policy_document WHERE required=true",id);
            }
            for(int id=1;id<=40;id++)jdbc.update("INSERT INTO toilet VALUES(?,?,36.3,127.3)",id,"격리 시험 화장실 "+id);
            // Optional operator-supplied synthetic facility; never change a real facility or spoof device GPS.
            if(System.getenv("REVIEW_FIXTURE_LATITUDE")!=null){
                double lat=Double.parseDouble(System.getenv("REVIEW_FIXTURE_LATITUDE")),lon=Double.parseDouble(System.getenv("REVIEW_FIXTURE_LONGITUDE"));
                if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat< -90||lat>90||lon< -180||lon>180)throw new IllegalArgumentException("Invalid fixture coordinates");
                jdbc.update("UPDATE toilet SET latitude=?,longitude=? WHERE toilet_id<=3",lat,lon);
            }
            // Historical fixture data exercises cursor/date/expiry without waiting weeks or changing the clock.
            for(int owner=1;owner<=3;owner++)for(int i=0;i<24;i++)jdbc.update("""
                    INSERT INTO toilet_review(review_key,toilet_id,author_user_id,satisfaction,cleanliness,paper_available,wait_minutes,comment,created_at,updated_at)
                    VALUES(UUID(),?,?,4,5,true,0,'합성 과거 리뷰',DATE_SUB(NOW(6),INTERVAL ? DAY),DATE_SUB(NOW(6),INTERVAL ? DAY))
                    """,10+i,owner,2+i,2+i);
            return ds;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds){return new JdbcTemplate(ds);}
        // Real journal algorithm with synthetic in-memory transports; no live GitHub or production keys.
        @Bean ReviewUnlinkProtection reviewUnlinkProtection(){
            var objects=new com.geupddong.account.ErasureObjectStore(){
                final java.util.TreeMap<String,byte[]> values=new java.util.TreeMap<>();
                public byte[] read(String key){return values.get(key);}
                public void putIfAbsent(String key,byte[] bytes){values.putIfAbsent(key,bytes);}
                public List<String> list(String prefix,int maximum){var keys=values.keySet().stream().filter(k->k.startsWith(prefix)).toList();if(keys.size()>maximum)throw new IllegalStateException();return keys;}
            };
            String realm=com.geupddong.review.ReviewUnlinkRecord.REALM,epoch=UUID.randomUUID().toString();
            var seed=new com.geupddong.account.ErasureCheckpoint(1,realm,epoch,1,0,"a".repeat(64),"",Instant.now().toString());
            var store=new com.geupddong.account.CheckpointedErasureLedger.Store(){
                com.geupddong.account.CheckpointedErasureLedger.Head head=new com.geupddong.account.CheckpointedErasureLedger.Head("a".repeat(40),new com.geupddong.account.ErasureCheckpoint(1,realm,epoch,1,0,seed.inventoryDigest(Map.of()),"",seed.recordedAt()));
                public com.geupddong.account.CheckpointedErasureLedger.Head read(){return head;}
                public void append(com.geupddong.account.CheckpointedErasureLedger.Head expected,com.geupddong.account.ErasureCheckpoint next){if(!head.equals(expected))throw new IllegalStateException();head=new com.geupddong.account.CheckpointedErasureLedger.Head(String.format("%040x",next.sequence()),next);}
            };
            byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
            var cipher=new com.geupddong.account.ErasureCipher("fixture",Map.of("fixture",Base64.getEncoder().encodeToString(key)));
            var journal=new com.geupddong.review.ReviewUnlinkJournal(objects,cipher,store,work->{synchronized(objects){work.run();}},epoch,Clock.systemUTC());
            return journal::ensureRecorded;
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory=new LocalContainerEntityManagerFactoryBean();factory.setDataSource(ds);
            factory.setPackagesToScan("com.example.toiletapi.auth.model","com.example.toiletapi.policy.model");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","none"));return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory emf){return new JpaTransactionManager(emf);}
        @Bean AuthTokenProperties authTokenProperties(){
            byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);
            return new AuthTokenProperties(Base64.getEncoder().encodeToString(bytes),Duration.ofHours(1),Duration.ofHours(1));
        }
        @Bean ClientRegistrationRepository clientRegistrationRepository(){
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("fixture")
                    .clientId("synthetic-only").clientSecret("unused-synthetic-only").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("http://127.0.0.1/unused").authorizationUri("http://127.0.0.1/unused")
                    .tokenUri("http://127.0.0.1/unused").userInfoUri("http://127.0.0.1/unused").userNameAttributeName("sub").build());
        }
        @Bean OAuthLoginSuccessHandler oauthLoginSuccessHandler(){
            return new OAuthLoginSuccessHandler(null,null,"http://127.0.0.1",null,null){
                @Override public void onAuthenticationSuccess(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,
                        org.springframework.security.core.Authentication auth) throws java.io.IOException {response.sendError(403,"OAuth not available in fixture");}
            };
        }
    }
    @org.springframework.boot.test.context.TestComponent @RestController static class FixtureReads {
        private final AppUserRepository users;private final PolicyConsentService policies;private final JdbcTemplate jdbc;
        FixtureReads(AppUserRepository users,PolicyConsentService policies,JdbcTemplate jdbc){this.users=users;this.policies=policies;this.jdbc=jdbc;}
        @GetMapping("/api/v1/auth/me") Object me(@AuthenticationPrincipal Jwt jwt){
            var user=users.findById(Long.parseLong(jwt.getSubject())).orElseThrow();
            return Map.of("userId",user.getId(),"displayName",user.getDisplayName(),"status",user.getStatus(),"roles",List.of("USER"),"consentRequired",policies.status(user.getId()).consentRequired());
        }
        @GetMapping("/api/v1/toilets/{id}") Object toilet(@PathVariable long id){
            String name=jdbc.queryForObject("SELECT name FROM toilet WHERE toilet_id=?",String.class,id);
            var detail=new HashMap<String,Object>();detail.put("id",id);detail.put("name",name);
            detail.put("latitude",jdbc.queryForObject("SELECT latitude FROM toilet WHERE toilet_id=?",Double.class,id));
            detail.put("longitude",jdbc.queryForObject("SELECT longitude FROM toilet WHERE toilet_id=?",Double.class,id));detail.put("toiletType","공중화장실");
            for(String field:List.of("roadAddress","jibunAddress","agencyName","phoneNumber","openTime","openTimeDetail","installationDate","hasEmergencyBell","emergencyBellLocation","hasCctv","hasDiaperTable","diaperTableLocation","dataBaseDate","dataSource"))detail.put(field,"");
            for(String field:List.of("maleToiletCount","maleUrinalCount","maleDisabledToiletCount","maleDisabledUrinalCount","maleChildToiletCount","maleChildUrinalCount","femaleToiletCount","femaleDisabledToiletCount","femaleChildToiletCount"))detail.put(field,0);
            return detail;
        }
        @GetMapping("/api/v1/toilets") Object map(){return Map.of("meta",Map.of("map_level",3,"display_type","MARKER","total_count",0,"result_count",0),"toilets",List.of(),"clusters",List.of());}
        @GetMapping("/api/v1/notifications/unread-count") Object unread(){return Map.of("count",0);}
    }
}
