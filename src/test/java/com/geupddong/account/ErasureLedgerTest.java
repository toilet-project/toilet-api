package com.geupddong.account;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ErasureLedgerTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private ErasureCipher cipher() { return new ErasureCipher("k1", Map.of("k1", KEY)); }
    private ErasureRecord record() { return new ErasureRecord(1, "production", 42, "2026-01-01T12:00",
            "01234567-1234-1234-1234-123456789012", "2026-09-06T12:00"); }
    private static S3Exception missing() { return (S3Exception) S3Exception.builder().statusCode(404).build(); }

    @Test void ciphertextHasRandomNonceAndRoundTripsWithoutPlainIdentifiers() throws Exception {
        var cipher = cipher(); var record = record();
        byte[] first = cipher.encrypt(record), second = cipher.encrypt(record);
        assertFalse(Arrays.equals(first, second));
        assertFalse(new String(first, StandardCharsets.UTF_8).contains(record.withdrawalKey()));
        assertEquals(record, cipher.decrypt(record.realm(), record.objectKey(), first));
        String fixtureFile = System.getenv("ERASURE_LEDGER_SMOKE_FILE");
        if (fixtureFile != null) {
            var fixture = new ErasureRecord(1, "verification", Long.MAX_VALUE, "2000-01-01T00:00",
                    "01234567-1234-1234-1234-123456789012", "2000-04-01T00:00");
            java.nio.file.Files.write(java.nio.file.Path.of(fixtureFile), cipher.encrypt(fixture));
        }
    }
    @Test void tamperingWrongRealmWrongObjectAndWrongKeyAreRejected() {
        var cipher = cipher(); var record = record(); byte[] bytes = cipher.encrypt(record);
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("staging", record.objectKey(), bytes));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(record.realm(), "other", bytes));
        byte[] wrong = new byte[32]; Arrays.fill(wrong, (byte)1);
        var otherKey = new ErasureCipher("k1", Map.of("k1", Base64.getEncoder().encodeToString(wrong)));
        assertThrows(IllegalStateException.class, () -> otherKey.decrypt(record.realm(), record.objectKey(), bytes));
        bytes[bytes.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(record.realm(), record.objectKey(), bytes));
    }
    @Test void rotatedKeyRingStillReadsOldObjectsAndRejectsUnknownKey() {
        byte[] bytes = cipher().encrypt(record());
        byte[] newKey = new byte[32]; Arrays.fill(newKey, (byte)2);
        String encoded = Base64.getEncoder().encodeToString(newKey);
        var rotated = new ErasureCipher("k2", Map.of("k1", KEY, "k2", encoded));
        assertEquals(record(), rotated.decrypt(record().realm(), record().objectKey(), bytes));
        var withoutOldKey = new ErasureCipher("k2", Map.of("k2", encoded));
        assertThrows(IllegalStateException.class, () -> withoutOldKey.decrypt(record().realm(), record().objectKey(), bytes));
    }
    @Test void disabledLedgerFailsClosedAndNonR2EndpointIsRejected() {
        var ledger = ErasureLedgerFactory.create(new MockEnvironment());
        assertThrows(IllegalStateException.class, () -> ledger.ensureRecorded(record()));
        var environment = new MockEnvironment().withProperty("erasure.ledger.enabled","true")
                .withProperty("erasure.ledger.realm","production").withProperty("erasure.ledger.bucket","example")
                .withProperty("erasure.ledger.endpoint","https://example.com");
        assertThrows(IllegalStateException.class, () -> ErasureLedgerFactory.create(environment));
    }
    @Test void writesConditionallyAndVerifiesReadbackBeforeReturning() {
        var s3 = mock(S3Client.class); var cipher = cipher();
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(missing())
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), cipher.encrypt(record())));
        var ledger = new R2ErasureLedger(s3, cipher, "bucket", "production");
        assertDoesNotThrow(() -> ledger.ensureRecorded(record()));
        var request = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertEquals("*", request.getValue().ifNoneMatch());
        assertEquals("no-store", request.getValue().cacheControl());
        verify(s3, times(2)).getObjectAsBytes(any(GetObjectRequest.class));
    }
    @Test void existingMatchingIntentIsIdempotentAndDoesNotOverwrite() {
        var s3 = mock(S3Client.class); var cipher = cipher();
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), cipher.encrypt(record())));
        new R2ErasureLedger(s3, cipher, "bucket", "production").ensureRecorded(record());
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
    @Test void failedPutAndMismatchingReadbackNeverAcknowledge() {
        var s3 = mock(S3Client.class); var cipher = cipher();
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(missing());
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(503).message("private").build());
        var ledger = new R2ErasureLedger(s3, cipher, "bucket", "production");
        var error = assertThrows(IllegalStateException.class, () -> ledger.ensureRecorded(record()));
        assertEquals("ERASURE_LEDGER_UNAVAILABLE", error.getMessage());
        assertNull(error.getCause());
        reset(s3);
        var wrongRecord = new ErasureRecord(1,"production",43,record().userCreatedAt(),record().withdrawalKey(),record().eligibleAt());
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), cipher.encrypt(wrongRecord)));
        assertThrows(IllegalStateException.class, () -> ledger.ensureRecorded(record()));
    }
    @Test void lostPutAcknowledgementCanBeRetriedAgainstExistingIntent() {
        var s3 = mock(S3Client.class); var cipher = cipher();
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(missing())
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), cipher.encrypt(record())));
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(504).build());
        var ledger = new R2ErasureLedger(s3, cipher, "bucket", "production");
        assertThrows(IllegalStateException.class, () -> ledger.ensureRecorded(record()));
        assertDoesNotThrow(() -> ledger.ensureRecorded(record()));
    }
    @Test void restoreSnapshotRequiresExpectedCountAndAllCiphertextsValid() {
        var s3 = mock(S3Client.class); var cipher = cipher();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key(record().objectKey()).size(500L).build()).isTruncated(false).build());
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), cipher.encrypt(record())));
        var ledger = new R2ErasureLedger(s3, cipher, "bucket", "production");
        assertEquals(List.of(record()), ledger.readAll(1));
        assertThrows(IllegalStateException.class, () -> ledger.readAll(2));
        assertThrows(IllegalStateException.class, () -> ledger.readAll(0));
    }
}
