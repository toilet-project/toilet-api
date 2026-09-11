package com.example.toiletapi.review;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** CI: the same SQL/concurrency/erasure suite against disposable MySQL, never the service DB. */
@Testcontainers(disabledWithoutDocker=true)
class ReviewContainerMySqlTest extends ReviewDatabaseTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0.40");
    @Override @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        var jdbc=new JdbcTemplate(ds);
        // The container belongs exclusively to this class. Its database is reset before each inherited test.
        for(String table:jdbc.query("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()",(rs,n)->rs.getString(1))) {
            if(!table.matches("[a-z_]+"))throw new IllegalStateException("Unexpected fixture table");
        }
        try(var connection=ds.getConnection();var statement=connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            try(var tables=connection.getMetaData().getTables(connection.getCatalog(),null,"%",new String[]{"TABLE"})) {
                var names=new java.util.ArrayList<String>();
                while(tables.next())names.add(tables.getString("TABLE_NAME"));
                for(String table:names) {
                    if(!table.matches("[a-z_]+"))throw new IllegalStateException("Unexpected fixture table");
                    statement.execute("DROP TABLE `"+table+"`");
                }
            }
            statement.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        setup(ds,false);
    }
}
