package com.example.toiletapi.toilet.translation;

import com.example.toiletapi.auth.support.NativeMySqlFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ACCOUNT_RETENTION_MYSQL_MARKER", matches = "[a-f0-9]{10}")
class ToiletTranslationNativeMySqlTest {
    private final ToiletTranslationMigrationMySqlTest checks = new ToiletTranslationMigrationMySqlTest();

    @BeforeEach void setup() {
        checks.setup(NativeMySqlFixture.create());
    }

    @Test void migrationBackfillsKoreanAndExplicitSyncKeepsSourceCurrent() {
        checks.migrationBackfillsKoreanAndExplicitSyncKeepsSourceCurrent();
    }

    @Test void bulkReadReturnsOnlyTranslationsForTheCurrentKoreanSource() {
        checks.bulkReadReturnsOnlyTranslationsForTheCurrentKoreanSource();
    }
}
