package com.example.toiletapi.report;

import com.example.toiletapi.auth.support.NativeMySqlFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ACCOUNT_RETENTION_MYSQL_MARKER", matches = "[a-f0-9]{10}")
class NewFacilityCountNativeMySqlTest {
    private final NewFacilityCountMigrationMySqlTest checks = new NewFacilityCountMigrationMySqlTest();

    @BeforeEach void setup() { checks.setup(NativeMySqlFixture.create()); }
    @AfterEach void close() { checks.close(); }
    @Test void migrationPreservesExistingCountsAndOmittedColumnDefaults() {
        checks.migrationPreservesExistingCountsAndOmittedColumnDefaults();
    }
    @Test void failedLegacyApprovalRollsBackAndTheSamePendingReportCanBeApprovedAfterMigration() {
        checks.failedLegacyApprovalRollsBackAndTheSamePendingReportCanBeApprovedAfterMigration();
    }
    @Test void confirmedZeroAndPositiveCountsStayDistinctFromUnknownAndTheProposalIsPreserved() {
        checks.confirmedZeroAndPositiveCountsStayDistinctFromUnknownAndTheProposalIsPreserved();
    }
}
