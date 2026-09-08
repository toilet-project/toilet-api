package com.geupddong.account;

import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import static org.junit.jupiter.api.Assertions.*;

/** HEAD/LIST only for R2, read only for the independent checkpoint. No Spring context or DB. */
class UsLedgerCutoverReadOnlyTest {
    static void require(boolean ok) { if (!ok) throw new IllegalStateException("READONLY_CHECK_REJECTED"); }
    static String required(String key) {
        String value=System.getenv(key); require(value!=null&&!value.isBlank()); return value;
    }
    static String usEndpoint(String oldEndpoint) {
        require(oldEndpoint!=null && oldEndpoint.matches("https://[a-f0-9]{32}\\.r2\\.cloudflarestorage\\.com/?"));
        return oldEndpoint.replace(".r2.",".us.r2.");
    }
    static void emptyCheckpoint(ErasureCheckpoint cp,String epoch) {
        require(cp.realm().equals("production") && cp.databaseEpoch().equals(epoch));
        require(!Instant.parse(cp.recordedAt()).isAfter(Instant.now()));
        // This is a no-migration cutover guard. Nonempty history requires a separate migration review.
        require(cp.count()==0 && cp.inventorySha256().equals(cp.inventoryDigest(Map.of())));
    }
    @Test void destinationMustBeTheSameAccountWithUsJurisdiction() {
        String old="https://"+"a".repeat(32)+".r2.cloudflarestorage.com";
        assertEquals(old.replace(".r2.",".us.r2."),usEndpoint(old));
        for(String invalid:List.of("http://example.com",old+"?redirect=1",old.replace("https://","https://user@"),old.replace(".r2.",".eu.r2.")))
            assertThrows(IllegalStateException.class,()->usEndpoint(invalid));
    }
    @Test void rejectNonemptyWrongIdentityAndInconsistentEmptyCheckpoint() {
        String epoch="22222222-2222-2222-2222-222222222222";
        String time=Instant.now().minusSeconds(10).toString();
        var seed=new ErasureCheckpoint(1,"production",epoch,1,0,"0".repeat(64),"",time);
        var empty=new ErasureCheckpoint(1,"production",epoch,1,0,seed.inventoryDigest(Map.of()),"",time);
        assertDoesNotThrow(()->emptyCheckpoint(empty,epoch));
        assertThrows(IllegalStateException.class,()->emptyCheckpoint(seed,epoch));
        assertThrows(IllegalStateException.class,()->emptyCheckpoint(empty,"33333333-3333-3333-3333-333333333333"));
        assertThrows(IllegalStateException.class,()->emptyCheckpoint(new ErasureCheckpoint(1,"production",epoch,2,1,empty.inventorySha256(),empty.digest(),time),epoch));
    }
    static S3Client client(String endpoint,String idName,String secretName) {
        return S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of("auto"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(required(idName),required(secretName))))
            .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(5)))
            .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(5)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
    }
    static void emptyProduction(S3Client s3,String bucket) {
        s3.headBucket(r->r.bucket(bucket));
        for(String prefix:List.of("v1/production/","catalogue-v1/production/","completion-v1/production/")) {
            var result=s3.listObjectsV2(r->r.bucket(bucket).prefix(prefix).maxKeys(1));
            require(result.contents().isEmpty() && !Boolean.TRUE.equals(result.isTruncated()));
        }
    }
    @Test
    @EnabledIfEnvironmentVariable(named="US_CUTOVER_READONLY_CHECK",matches="approved")
    void apiStoredKeysAndBothLedgersMatchIndependentEmptyCheckpoint() {
        String stage="configuration";
        try {
            String oldEndpoint=required("OLD_ENDPOINT"),us=usEndpoint(oldEndpoint);
            var store=GitHubErasureCheckpointStore.configured(required("CHECKPOINT_TOKEN"));
            String epoch=required("CHECKPOINT_DATABASE_EPOCH");
            stage="checkpoint-before";
            var before=store.read(); emptyCheckpoint(before.checkpoint(),epoch);
            try(var old=client(oldEndpoint,"OLD_ACCESS_KEY_ID","OLD_SECRET_ACCESS_KEY");
                var next=client(us,"US_ACCESS_KEY_ID","US_SECRET_ACCESS_KEY")) {
                stage="old-production"; emptyProduction(old,"geupddong-account-erasure-ledger");
                stage="us-production"; emptyProduction(next,"geupddong-account-erasure-ledger-us");
                stage="repeat-snapshot";
                emptyProduction(old,"geupddong-account-erasure-ledger");
                emptyProduction(next,"geupddong-account-erasure-ledger-us");
                require(before.equals(store.read()));
                System.out.println("US_CUTOVER_READONLY_PASS apiNewKeyAccess=true oldProductionCount=0 usProductionCount=0 checkpointCount=0 identityMatch=true inventoryHashMatch=true snapshotStable=true r2Writes=false githubWrites=false dbConnection=false objectContentsRead=false");
            }
        } catch(Throwable ignored) {
            System.out.println("US_CUTOVER_READONLY_FAILED stage="+stage+" detailsSuppressed=true");
            throw new AssertionError("READONLY_CHECK_FAILED detailsSuppressed=true");
        }
    }
}
