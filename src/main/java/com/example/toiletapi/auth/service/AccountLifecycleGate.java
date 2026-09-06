package com.example.toiletapi.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Startup-configured gate, not a distributed lock. Restart/drain every writer when changing it. */
@Component
public class AccountLifecycleGate {
    private final boolean maintenance;
    private final boolean retentionEnabled;
    private final boolean erasureEnabled;
    public AccountLifecycleGate(@Value("${account.lifecycle.maintenance:true}") boolean maintenance,
            @Value("${account.retention.enabled:false}") boolean retentionEnabled,
            @Value("${account.erasure.enabled:false}") boolean erasureEnabled) {
        this.maintenance = maintenance;
        this.retentionEnabled = retentionEnabled;
        this.erasureEnabled = erasureEnabled;
    }
    public boolean withdrawalAvailable() { return !maintenance && retentionEnabled && erasureEnabled; }
    public void requireWithdrawal() { require(withdrawalAvailable()); }
    public void requireRecovery() { require(!maintenance && retentionEnabled); }
    public void requireErasure() { require(!maintenance && erasureEnabled); }
    private void require(boolean allowed) {
        if (!allowed) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "계정 복구·파기 기능 점검 중입니다. 개인정보 문의로 요청해 주세요.");
    }
}
