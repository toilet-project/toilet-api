package com.example.toiletapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.model.AuditLog;
import com.example.toiletapi.auth.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AuditLogServiceTest {

    @Test
    void masksSensitiveDetailsBeforePersistingAuditLog() {
        AuditLogRepository repository = Mockito.mock(AuditLogRepository.class);
        AuditLogService service = new AuditLogService(repository, new ObjectMapper());

        service.recordReportDecision(1L, 10L, AuditAction.REPORT_APPROVED,
                Map.of("reason", "위치 확인", "accessToken", "raw-token", "email", "user@example.com"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        AuditLog log = captor.getValue();
        assertThat(log.getAction()).isEqualTo("REPORT_APPROVED");
        assertThat(log.getTargetType()).isEqualTo("TOILET_REPORT");
        assertThat(log.getDetailJson()).contains("위치 확인", "[REDACTED]")
                .doesNotContain("raw-token", "user@example.com");
    }
}
