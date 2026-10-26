package com.example.toiletapi.growth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ReadOnlyGrowthRankingPreviewDataSourceTest {
    List<String> actualQueries() {
        var queries=new ArrayList<String>();
        var jdbc=new JdbcTemplate() {
            @Override public <T>List<T> query(String sql,RowMapper<T> mapper,Object... args) {queries.add(sql);return List.of();}
            @Override public <T>T queryForObject(String sql,Class<T> type,Object... args) {queries.add(sql);return type.cast(0L);}
            @Override public void query(String sql,org.springframework.jdbc.core.RowCallbackHandler handler,Object... args) {queries.add(sql);}
        };
        var repository=new AdminGrowthRankingRepository(jdbc);
        repository.page(null,null,false,10,0);repository.page(null,null,true,10,0);repository.count(null,null);repository.distribution();
        return queries;
    }
    record Raw(Connection connection,Statement setup,PreparedStatement select) { }
    Raw raw(int readOnly) throws Exception {
        var connection=mock(Connection.class);var setup=mock(Statement.class);var result=mock(ResultSet.class);var select=mock(PreparedStatement.class);
        when(connection.getAutoCommit()).thenReturn(true);when(connection.createStatement()).thenReturn(setup);
        when(setup.executeQuery("SELECT @@session.transaction_read_only")).thenReturn(result);
        when(result.next()).thenReturn(true);when(result.getInt(1)).thenReturn(readOnly);
        when(connection.prepareStatement(anyString())).thenReturn(select);
        return new Raw(connection,setup,select);
    }
    @Test void exactAllowlistMatchesAllActualRepositoryShapesAndRefusesEveryOtherSql() {
        var sql=actualQueries();assertEquals(4,sql.size());sql.forEach(query->assertTrue(ReadOnlyGrowthRankingPreviewDataSource.permits(query)));
        for(String denied:List.of("SELECT 1","SELECT * FROM app_user","DELETE FROM toilet_review","SET SESSION TRANSACTION READ WRITE",
                sql.getFirst()+"; SELECT 1",sql.getFirst()+" /* harmless */",sql.getFirst().replace("LIMIT ?","LIMIT 50000")))
            assertFalse(ReadOnlyGrowthRankingPreviewDataSource.permits(denied));
    }
    @Test void mysqlReadOnlySessionIsConfiguredAndVerifiedBeforeAnyRepositoryStatement() throws Exception {
        var raw=raw(1);var source=new ReadOnlyGrowthRankingPreviewDataSource(()->raw.connection());
        try(var guarded=source.getConnection()) {
            verify(raw.connection()).setReadOnly(true);
            verify(raw.setup()).execute("SET SESSION TRANSACTION READ ONLY");verify(raw.setup()).execute("SET SESSION MAX_EXECUTION_TIME=3000");
            verify(raw.setup()).execute("SET SESSION time_zone='+09:00'");
            try(var select=guarded.prepareStatement(actualQueries().getFirst())) {
                verify(raw.select()).setQueryTimeout(3);assertSame(guarded,select.getConnection());
                assertThrows(SQLException.class,()->select.executeUpdate());assertThrows(SQLException.class,()->select.execute("DELETE FROM toilet_review"));
                assertThrows(SQLException.class,()->select.executeQuery("SELECT 1"));assertThrows(SQLException.class,()->select.setQueryTimeout(0));
                select.executeQuery();verify(raw.select()).executeQuery();
            }
            assertThrows(SQLException.class,guarded::createStatement);assertThrows(SQLException.class,()->guarded.prepareCall("SELECT 1"));
            assertThrows(SQLException.class,()->guarded.setReadOnly(false));assertThrows(SQLException.class,()->guarded.unwrap(Connection.class));
            assertThrows(SQLException.class,()->guarded.prepareStatement("DELETE FROM toilet_review"));
        }
        verify(raw.connection()).close();
    }
    @Test void failedNativeProofFailsClosedWithNoConnectionDetailAndReleasesCapacity() throws Exception {
        var raw=raw(0);var source=new ReadOnlyGrowthRankingPreviewDataSource(()->raw.connection());
        for(int i=0;i<6;i++) {
            SQLException failure=assertThrows(SQLException.class,source::getConnection);
            assertEquals("Read-only preview database unavailable",failure.getMessage());assertNull(failure.getCause());
            assertEquals(ReadOnlyGrowthRankingPreviewDataSource.Stage.NATIVE_PROOF,((ReadOnlyGrowthRankingPreviewDataSource.InitializationFailure)failure).stage());
        }
        verify(raw.connection(),times(6)).close();verify(raw.connection(),never()).prepareStatement(anyString());
    }
    @Test void startupDiagnosticsUseFixedStagesOnlyAndNeverRepeatOnOrdinaryRequests() throws Exception {
        var raw=raw(1);var source=new ReadOnlyGrowthRankingPreviewDataSource(()->raw.connection());
        var stages=new ArrayList<ReadOnlyGrowthRankingPreviewDataSource.Stage>();source.verifyConnection(stages::add);
        assertEquals(List.of(ReadOnlyGrowthRankingPreviewDataSource.Stage.values()),stages);
        source.verifyConnection();assertEquals(8,stages.size());
        when(raw.connection().getAutoCommit()).thenThrow(new SQLException("synthetic password and SQL must never be copied","08001"));
        var failure=assertThrows(ReadOnlyGrowthRankingPreviewDataSource.InitializationFailure.class,source::verifyConnection);
        assertEquals(ReadOnlyGrowthRankingPreviewDataSource.Stage.AUTOCOMMIT_CHECK,failure.stage());
        assertEquals(ReadOnlyGrowthRankingPreviewDataSource.Reason.CONNECTIVITY,failure.reason());assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("synthetic"));
    }
    @Test void atMostFourConnectionsExistAndClosingTwiceDoesNotExpandCapacity() throws Exception {
        var raw=raw(1);var source=new ReadOnlyGrowthRankingPreviewDataSource(()->raw.connection());
        var connections=new ArrayList<Connection>();for(int i=0;i<4;i++)connections.add(source.getConnection());
        assertThrows(SQLTransientConnectionException.class,source::getConnection);
        connections.getFirst().close();connections.getFirst().close();connections.set(0,source.getConnection());
        assertThrows(SQLTransientConnectionException.class,source::getConnection);
        for(var connection:connections)connection.close();
    }
    @Test void configurationRejectsNonServiceDatabaseOrDriverEscapeProperties() {
        var env=new HashMap<>(Map.of("SPRING_DB_URL","jdbc:mysql://toilet-mysql:3306/toilet_db","SPRING_DB_USERNAME","existing","SPRING_DB_PASSWORD","synthetic"));
        assertNotNull(ReadOnlyGrowthRankingPreviewDataSource.fromEnvironment(env));
        for(String url:List.of("jdbc:h2:mem:unsafe","jdbc:mysql://toilet-mysql:3306/other","jdbc:mysql://toilet-mysql:3306/toilet_db?allowMultiQueries=true",
                "jdbc:mysql://toilet-mysql:3306/toilet_db?sessionVariables=transaction_read_only=0","jdbc:mysql://user:password@toilet-mysql:3306/toilet_db")) {
            env.put("SPRING_DB_URL",url);assertThrows(IllegalArgumentException.class,()->ReadOnlyGrowthRankingPreviewDataSource.fromEnvironment(env));
        }
    }
    @Test void liveHostRequiresExplicitOptInBeforeContactingDatabase() {
        assertThrows(IllegalArgumentException.class,()->ExperienceRankingLivePreviewServer.start(new String[0],Map.of()));
    }
}
