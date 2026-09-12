package com.example.toiletapi.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geupddong.account.*;
import com.geupddong.review.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class ReviewUnlinkConfiguration {
    @Bean(destroyMethod="close") @ConditionalOnProperty(name="reviews.unlink-enabled",havingValue="true")
    ReviewUnlinkJournal reviewUnlinkJournal(Environment env,JdbcTemplate jdbc){
        try{
            if(!"LOCAL".equals(env.getProperty("erasure.ledger.provider"))
                    || !env.getProperty("reviews.unlink-local-verified",Boolean.class,false))throw new IllegalStateException();
            var keys=new ObjectMapper().readValue(env.getRequiredProperty("erasure.ledger.keys-json"),new TypeReference<Map<String,String>>(){});
            var directory=Path.of(env.getRequiredProperty("reviews.unlink-directory")).toAbsolutePath().normalize();
            var accountDirectory=Path.of(env.getRequiredProperty("erasure.ledger.local-directory")).toAbsolutePath().normalize();
            if(directory.startsWith(accountDirectory) || accountDirectory.startsWith(directory))throw new IllegalStateException();
            var objects=new FileErasureObjectStore(directory,ReviewUnlinkRecord.REALM,env.getRequiredProperty("reviews.unlink-store-id"));
            var cipher=new ErasureCipher(env.getRequiredProperty("erasure.ledger.active-key-id"),keys);
            var checkpoints=ReviewCheckpointStore.configured(env.getRequiredProperty("ERASURE_CHECKPOINT_GITHUB_TOKEN"));
            var journal=new ReviewUnlinkJournal(objects,cipher,checkpoints,exclusive(jdbc),env.getRequiredProperty("ERASURE_CHECKPOINT_DATABASE_EPOCH"),Clock.systemUTC());
            journal.snapshot(); // Missing genesis/store, wrong epoch, incomplete or lost inventory refuses startup.
            return journal;
        }catch(Exception ignored){throw new IllegalStateException("REVIEW_UNLINK_CONFIGURATION_INVALID");}
    }
    @Bean ReviewUnlinkProtection reviewUnlinkProtection(ObjectProvider<ReviewUnlinkJournal> journal){
        return key->{
            try{var configured=journal.getIfAvailable();if(configured==null)throw new IllegalStateException();configured.ensureRecorded(key);}
            catch(RuntimeException ignored){throw new ReviewFailure(503,"REVIEW_UNLINK_UNAVAILABLE","작성자 정보를 안전하게 지우지 못했어요. 잠시 후 다시 시도해 주세요.");}
        };
    }
    private static CheckpointedErasureLedger.Exclusive exclusive(JdbcTemplate jdbc){
        return work->jdbc.execute((ConnectionCallback<Void>)connection->{
            boolean held=false;
            try{
                try(var statement=connection.prepareStatement("SELECT GET_LOCK('geupddong-review-unlink-v1',2)")){
                    statement.setQueryTimeout(3);try(var result=statement.executeQuery()){held=result.next() && result.getInt(1)==1 && !result.wasNull();}
                }catch(Exception e){try{connection.abort(Runnable::run);}catch(Exception ignored){}throw e;}
                if(!held)throw new IllegalStateException("REVIEW_UNLINK_BUSY");work.run();return null;
            }finally{
                if(held)try(var statement=connection.prepareStatement("SELECT RELEASE_LOCK('geupddong-review-unlink-v1')")){
                    statement.setQueryTimeout(3);try(var result=statement.executeQuery()){if(!result.next() || result.getInt(1)!=1 || result.wasNull())throw new IllegalStateException();}
                }catch(Exception e){try{connection.abort(Runnable::run);}catch(Exception ignored){}throw e;}
            }
        });
    }
}
