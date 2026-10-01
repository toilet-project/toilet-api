package com.example.toiletapi.engagement;
import com.example.toiletapi.auth.support.NativeMySqlFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="ACCOUNT_RETENTION_MYSQL_MARKER",matches="[a-f0-9]{10}")
class EngagementNativeMySqlTest extends EngagementDatabaseTest {
    @Override @BeforeEach void setup() throws Exception {setup(NativeMySqlFixture.create());}
}
