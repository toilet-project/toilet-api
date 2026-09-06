package com.example.toiletapi.auth.service;

import com.example.toiletapi.auth.repository.AccountWithdrawalRepository;
import com.example.toiletapi.global.time.KoreanTime;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @EnableScheduling
public class AccountErasureScheduler {
    private static final Logger log = LoggerFactory.getLogger(AccountErasureScheduler.class);
    private final AccountWithdrawalRepository withdrawals;
    private final AccountErasureService erasure;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final AtomicLong overdue = new AtomicLong();
    public AccountErasureScheduler(AccountWithdrawalRepository withdrawals, AccountErasureService erasure,
            MeterRegistry metrics, @Value("${account.retention.enabled:false}") boolean enabled) {
        this.withdrawals = withdrawals; this.erasure = erasure; this.metrics = metrics; this.enabled = enabled;
        metrics.gauge("account.erasure.overdue", overdue);
    }

    @Scheduled(fixedDelayString = "${account.retention.poll-ms:60000}", initialDelayString = "${account.retention.poll-ms:60000}")
    public void run() {
        if (!enabled) return;
        var now = KoreanTime.now();
        for (Long id : withdrawals.findDue(now, PageRequest.of(0, 50))) {
            try {
                if (erasure.eraseIfDue(id, now)) metrics.counter("account.erasure.completed").increment();
            } catch (Exception failure) {
                metrics.counter("account.erasure.failed").increment();
                // Never put identifiers, nicknames, SQL arguments, or exception messages in operational logs.
                log.error("Account erasure failed; retry required (ERASURE_RETRY_REQUIRED)");
                try { erasure.recordFailure(id); }
                catch (Exception unavailable) { log.error("Account erasure retry checkpoint unavailable"); }
            }
        }
        overdue.set(withdrawals.countByPurgeAfterLessThanEqual(KoreanTime.now()));
    }
}
