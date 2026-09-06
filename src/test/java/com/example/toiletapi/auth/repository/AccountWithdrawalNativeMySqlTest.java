package com.example.toiletapi.auth.repository;

import com.example.toiletapi.auth.support.NativeMySqlFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ACCOUNT_RETENTION_MYSQL_MARKER", matches = "[a-f0-9]{10}")
class AccountWithdrawalNativeMySqlTest {
    @Test void isolatedNativeMysqlAcceptsV11() {
        AccountWithdrawalMySqlTest.verifyMigration(NativeMySqlFixture.create());
    }
}
