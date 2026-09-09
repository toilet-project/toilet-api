package com.example.toiletapi.auth.service;

import com.geupddong.account.LocalMaintenanceLease;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;

/** H2 SQL transaction + real POSIX lease + independent Python contender. Synthetic files only. */
@EnabledOnOs(OS.LINUX)
class AccountMaintenancePosixTest {
    Path directory, lock;
    JdbcTemplate jdbc;
    AccountMaintenanceTransactions transactions;
    @BeforeEach void setup() throws Exception {
        directory=Files.createTempDirectory("geupddong-transaction-lock.",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        lock=Files.createFile(directory.resolve(".maintenance.lock"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        int uid=((Number)Files.getAttribute(lock,"unix:uid")).intValue();
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds); jdbc.execute("create table fixture(id int)");
        transactions=new AccountMaintenanceTransactions(new DataSourceTransactionManager(ds),
                ()->{var held=LocalMaintenanceLease.acquire(lock,uid);return held::close;});
    }
    @AfterEach void cleanup() throws Exception {Files.deleteIfExists(lock);Files.deleteIfExists(directory);}
    int contender() {
        try {
            var process=new ProcessBuilder("python3","-c",
                    "import fcntl,sys; f=open(sys.argv[1],'r+'); fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)",lock.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();fail("contender timeout");}
            return process.exitValue();
        } catch(Exception ex){throw new AssertionError(ex);}
    }
    @Test void pythonCannotEnterUntilSqlCommitCompletes() {
        transactions.execute(()->{
            jdbc.update("insert into fixture values(1)"); assertNotEquals(0,contender());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){assertNotEquals(0,contender());}
            });return null;
        });
        assertEquals(0,contender());assertEquals(1,jdbc.queryForObject("select count(*) from fixture",Integer.class));
    }
    @Test void pythonCannotEnterUntilRollbackCompletes() {
        assertThrows(IllegalStateException.class,()->transactions.execute(()->{
            jdbc.update("insert into fixture values(1)");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCompletion(int status){assertNotEquals(0,contender());}
            });throw new IllegalStateException("fixture rollback");
        }));
        assertEquals(0,contender());assertEquals(0,jdbc.queryForObject("select count(*) from fixture",Integer.class));
    }
}
