package com.example.toiletapi.cache;

import com.example.toiletapi.auth.support.NativeMySqlFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Runs the real trigger/repository contract against a guarded, fresh local schema. */
@EnabledIfEnvironmentVariable(named="ACCOUNT_RETENTION_MYSQL_MARKER", matches="[a-f0-9]{10}")
class CacheInvalidationNativeMySqlTest {
    private final CacheInvalidationMySqlTest contract=new CacheInvalidationMySqlTest();
    @BeforeAll static void schema() {
        var source=NativeMySqlFixture.create();
        CacheInvalidationMySqlTest.schema(source,source);
    }
    @BeforeEach void clear() { contract.clear(); }
    @Test void temporaryHiddenRelatedWrites() { contract.hiddenHoursAndTranslationsRemainPrivateUntilExplicitRestore(); }
    @Test void visibilityRestore() { contract.visibilityChangesInvalidateBothDetailAndCatalog(); }
    @Test void revisionsAndOldAcknowledgement() { contract.repeatedMutationsCoalesceButOldAckCannotEraseANewerEvent(); }
    @Test void coordinateBounds() { contract.scopedEventKeepsEveryUndeliveredPositionAndClearsAfterAck(); }
    @Test void deletion() { contract.regionCompletionRemovalAndToiletDeletionAreAllCaptured(); }
    @Test void boundedDelivery() { contract.signedDeliveryBatchIsBoundedAndLeavesTheNextRowsDue(); }
}
