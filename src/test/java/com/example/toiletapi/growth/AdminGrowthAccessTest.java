package com.example.toiletapi.growth;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class AdminGrowthAccessTest {
    JdbcTemplate jdbc;
    AdminGrowthAccess access;
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:growth_access_" + UUID.randomUUID() + ";MODE=MySQL", "sa", ""));
        // Keep this isolated database alive between the short-lived JDBC connections.
        jdbc.execute("SET DB_CLOSE_DELAY -1");
        jdbc.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY,status VARCHAR(30),auth_version BIGINT)");
        jdbc.execute("CREATE TABLE user_role(user_id BIGINT,role VARCHAR(30))");
        jdbc.update("INSERT INTO app_user VALUES(1,'ACTIVE',2),(2,'ACTIVE',0),(3,'WITHDRAWN',0),(4,'ACTIVE',0)");
        jdbc.update("INSERT INTO user_role VALUES(1,'ADMIN'),(3,'ADMIN')");
        access = new AdminGrowthAccess(jdbc);
    }
    Jwt token(String id, long version) { return Jwt.withTokenValue("test").header("alg", "none").subject(id).claim("auth_version",version).claim("roles",List.of("ADMIN")).build(); }
    @Test void staleAdminRoleClaimCannotAuthorizeAfterRoleRemoval() {
        assertEquals(1, access.requireAdmin(token("1",2)));
        jdbc.update("DELETE FROM user_role WHERE user_id=1");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> access.requireAdmin(token("1",2))).getStatusCode());
    }
    @Test void staleVersionWithdrawnAndMissingSessionsCannotAuthorize() {
        for (Jwt jwt : List.of(token("1",1),token("3",0),token("99",0),token("-1",0))) {
            assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class, () -> access.requireAdmin(jwt)).getStatusCode());
        }
        assertThrows(ResponseStatusException.class, () -> access.requireAdmin(null));
    }
    @Test void candidatePagesAreBoundedStableAndExcludeWithdrawnAccounts() {
        var first=access.candidates(0,2);
        assertEquals(List.of("1","2"),first.userIds());assertEquals("2",first.nextAfterUserId());assertTrue(first.hasMore());
        var last=access.candidates(2,2);
        assertEquals(List.of("4"),last.userIds());assertNull(last.nextAfterUserId());assertFalse(last.hasMore());
        assertThrows(ResponseStatusException.class, () -> access.candidates(0,51));
        assertThrows(ResponseStatusException.class, () -> access.candidates(-1,1));
    }
}
