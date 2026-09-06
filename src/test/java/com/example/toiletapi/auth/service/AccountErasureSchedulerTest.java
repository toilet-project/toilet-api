package com.example.toiletapi.auth.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.toiletapi.auth.repository.AccountWithdrawalRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountErasureSchedulerTest {
    @Test void disabledByDefaultAndFailureDoesNotBlockOtherAccounts() {
        var repo = mock(AccountWithdrawalRepository.class); var erasure = mock(AccountErasureService.class);
        var metrics = new SimpleMeterRegistry();
        new AccountErasureScheduler(repo, erasure, metrics, false).run(); verifyNoInteractions(repo, erasure);
        when(repo.findDue(any(), any())).thenReturn(List.of(1L, 2L));
        when(erasure.eraseIfDue(eq(1L), any())).thenThrow(new IllegalStateException("private never logged"));
        when(erasure.eraseIfDue(eq(2L), any())).thenReturn(true);
        when(repo.countByPurgeAfterLessThanEqual(any())).thenReturn(1L);
        new AccountErasureScheduler(repo, erasure, metrics, true).run();
        verify(erasure).recordFailure(1L); verify(erasure).eraseIfDue(eq(2L), any());
        assertThat(metrics.counter("account.erasure.failed").count()).isEqualTo(1);
        assertThat(metrics.counter("account.erasure.completed").count()).isEqualTo(1);
    }
}
