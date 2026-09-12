package com.example.toiletapi.review;

import com.example.toiletapi.auth.support.NativeMySqlFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="ACCOUNT_RETENTION_MYSQL_MARKER",matches="[a-f0-9]{10}")
class ReviewNativeMySqlTest extends ReviewDatabaseTest {
    @Override @BeforeEach void setup() throws Exception {setup(NativeMySqlFixture.create(),false);}
    @Test void fourByteUnicodeIsTwoHundredCharactersInMysql() {
        assertEquals("😀".repeat(200),create("😀".repeat(200)).comment());
    }
}
