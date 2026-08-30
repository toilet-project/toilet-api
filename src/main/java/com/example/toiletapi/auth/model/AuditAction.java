package com.example.toiletapi.auth.model;

/** 감사 로그에 남기는 보안·관리 행위 코드다. */
public enum AuditAction {
    ROLE_GRANTED,
    ROLE_REVOKED,
    REPORT_APPROVED,
    REPORT_REJECTED,
    USER_SUSPENDED
}
