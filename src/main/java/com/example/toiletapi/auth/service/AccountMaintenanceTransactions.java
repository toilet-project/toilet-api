package com.example.toiletapi.auth.service;

import com.geupddong.account.LocalMaintenanceLease;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Owns the full SQL boundary. No lock release inside a transaction interceptor's method body. */
@Component
public class AccountMaintenanceTransactions {
    @FunctionalInterface interface Lease extends AutoCloseable { @Override void close(); }
    @FunctionalInterface interface Guard { Lease acquire(); }
    private final TransactionTemplate transaction;
    private final Guard guard;
    @org.springframework.beans.factory.annotation.Autowired
    public AccountMaintenanceTransactions(PlatformTransactionManager manager, Environment env) {
        this(manager,()->{
            if(!"LOCAL".equals(env.getProperty("ERASURE_LEDGER_PROVIDER"))
                    || !env.getProperty("ERASURE_MAINTENANCE_LOCK_ENABLED",Boolean.class,false)
                    || !"/home/luha/geupddong-maintenance".equals(env.getProperty("ERASURE_MAINTENANCE_DIRECTORY"))
                    || !"/home/luha/geupddong-erasure-ledger".equals(env.getProperty("ERASURE_LEDGER_LOCAL_DIRECTORY")))
                throw unavailable();
            try {
                var lock=LocalMaintenanceLease.acquire(Path.of("/home/luha/geupddong-maintenance/.maintenance.lock"),1000);
                return lock::close;
            } catch(IllegalStateException ignored) {throw unavailable();}
        });
    }
    // Package-private synthetic test seam, never selected by production configuration.
    AccountMaintenanceTransactions(PlatformTransactionManager manager, Guard guard) {
        this.transaction=new TransactionTemplate(manager); this.guard=guard;
    }
    public <T> T execute(Supplier<T> work) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        try(var lease=guard.acquire()) {return transaction.execute(status->work.get());}
    }
    /** Legacy OAuth already owns a SQL row lock. Nonblocking acquisition avoids inverse lock waits.
     * The calling transactional boundary must propagate failures (OAuth's interceptor does).
     */
    public <T> T executeErasure(Supplier<T> work) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) return execute(work);
        if(!TransactionSynchronizationManager.isSynchronizationActive()) throw unavailable();
        var lease=guard.acquire();
        try {
            TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization(){
                    @Override public void afterCompletion(int status){lease.close();}
                });
        } catch(RuntimeException failure) {lease.close(); throw failure;}
        return work.get();
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"계정 처리 점검 중입니다. 잠시 후 다시 시도해 주세요.");
    }
}
