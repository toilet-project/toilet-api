package com.example.toiletapi.auth.service;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class AccountMaintenanceTransactionsTest {
    JdbcTemplate jdbc; DataSourceTransactionManager manager;
    AtomicBoolean held; AccountMaintenanceTransactions tx;
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds); jdbc.execute("create table fixture(id int)");
        manager=new DataSourceTransactionManager(ds); held=new AtomicBoolean();
        tx=new AccountMaintenanceTransactions(manager,()->{
            assertFalse(held.getAndSet(true)); return ()->assertTrue(held.getAndSet(false));
        });
    }
    @Test void ownsCommitBoundary(){
        tx.execute(()->{
            assertTrue(held.get()); jdbc.update("insert into fixture values(1)");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){assertTrue(held.get());}
            }); return null;
        });
        assertFalse(held.get()); assertEquals(1,jdbc.queryForObject("select count(*) from fixture",Integer.class));
    }
    @Test void rollbackBeforeRelease(){
        assertThrows(IllegalStateException.class,()->tx.execute(()->{
            jdbc.update("insert into fixture values(1)");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCompletion(int status){assertTrue(held.get()); assertEquals(STATUS_ROLLED_BACK,status);}
            }); throw new IllegalStateException();
        }));
        assertFalse(held.get()); assertEquals(0,jdbc.queryForObject("select count(*) from fixture",Integer.class));
    }
    @Test void busyNeverStartsWork(){
        var busy=new AccountMaintenanceTransactions(manager,()->{throw new IllegalStateException();});
        assertThrows(IllegalStateException.class,()->busy.execute(()->{fail("work invoked");return null;}));
    }
    @Test void ordinaryOuterTransactionRejected(){
        new TransactionTemplate(manager).executeWithoutResult(s->
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->tx.execute(()->null)));
        assertFalse(held.get());
    }
    @Test void oauthJoinedErasureRetainsLeaseUntilCommit(){
        new TransactionTemplate(manager).executeWithoutResult(s->{
            tx.executeErasure(()->{jdbc.update("insert into fixture values(1)");return null;});
            assertTrue(held.get());
        }); assertFalse(held.get());
    }
    @Test void oauthJoinedErasureRetainsLeaseUntilRollback(){
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(s->{
            tx.executeErasure(()->{jdbc.update("insert into fixture values(1)");throw new IllegalStateException();});
        }));
        assertFalse(held.get()); assertEquals(0,jdbc.queryForObject("select count(*) from fixture",Integer.class));
    }
    @Test void missingProductionConfigIsServiceUnavailable(){
        var configured=new AccountMaintenanceTransactions(manager,new MockEnvironment());
        var failure=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->configured.execute(()->null));
        assertEquals(503,failure.getStatusCode().value());
    }
}
