package com.example.toiletapi.growth;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Preview only: native MySQL read-only sessions and an exact parameterized SELECT allowlist. */
final class ReadOnlyGrowthRankingPreviewDataSource extends AbstractDataSource {
    @FunctionalInterface interface Connections { Connection open() throws SQLException; }
    private final Connections connections;
    private final Semaphore slots=new Semaphore(4);
    enum Stage { CONNECT_OPEN, AUTOCOMMIT_CHECK, CLIENT_READ_ONLY, SESSION_READ_ONLY, SESSION_TIMEOUT, SESSION_TIMEZONE, NATIVE_PROOF, NATIVE_CONFIRMED }
    enum Reason { CONNECTIVITY, AUTHENTICATION, SQL_CONFIGURATION, DRIVER_OR_SESSION, UNAVAILABLE }
    static final class InitializationFailure extends SQLException {
        private final Stage stage;private final Reason reason;
        InitializationFailure(Stage stage,Reason reason) {super("Read-only preview database unavailable");this.stage=stage;this.reason=reason;}
        Stage stage(){return stage;} Reason reason(){return reason;}
    }
    private final ThreadLocal<Consumer<Stage>> startupDiagnostic=new ThreadLocal<>();
    private static final Set<String> ALLOWED=Set.of(
            normalized(AdminGrowthRankingRepository.PAGE_DESC),normalized(AdminGrowthRankingRepository.PAGE_ASC),
            normalized(AdminGrowthRankingRepository.COUNT),normalized(AdminGrowthRankingRepository.DISTRIBUTION));
    ReadOnlyGrowthRankingPreviewDataSource(Connections connections) { this.connections=connections; }
    static ReadOnlyGrowthRankingPreviewDataSource fromEnvironment(Map<String,String> env) {
        String url=env.get("SPRING_DB_URL"),user=env.get("SPRING_DB_USERNAME"),password=env.get("SPRING_DB_PASSWORD");
        if(url==null || !url.matches("jdbc:mysql://[a-zA-Z0-9.-]+(?::[0-9]{1,5})?/toilet_db(?:\\?[^#;]*)?")
                || url.matches("(?i).*[?&](?:user|password|allowMultiQueries|sessionVariables|autoReconnect|readOnlyPropagatesToServer)=.*")
                || user==null || user.isBlank() || password==null || password.isBlank())throw new IllegalArgumentException("Existing database configuration required");
        var source=new DriverManagerDataSource(url,user,password);var properties=new Properties();
        properties.setProperty("connectTimeout","3000");properties.setProperty("socketTimeout","5000");
        properties.setProperty("connectionTimeZone","+09:00");properties.setProperty("forceConnectionTimeZoneToSession","true");
        properties.setProperty("allowMultiQueries","false");properties.setProperty("readOnlyPropagatesToServer","true");
        source.setConnectionProperties(properties);return new ReadOnlyGrowthRankingPreviewDataSource(source::getConnection);
    }
    @Override public Connection getConnection() throws SQLException {
        if(!slots.tryAcquire())throw new SQLTransientConnectionException("Preview read capacity reached");
        Connection raw=null;Stage stage=Stage.CONNECT_OPEN;
        try {
            report(stage);raw=connections.open();stage=Stage.AUTOCOMMIT_CHECK;report(stage);
            if(!raw.getAutoCommit())throw new SQLException("Fresh autocommit session required");
            stage=Stage.CLIENT_READ_ONLY;report(stage);raw.setReadOnly(true);
            try(var statement=raw.createStatement()) {
                statement.setQueryTimeout(3);stage=Stage.SESSION_READ_ONLY;report(stage);statement.execute("SET SESSION TRANSACTION READ ONLY");
                stage=Stage.SESSION_TIMEOUT;report(stage);statement.execute("SET SESSION MAX_EXECUTION_TIME=3000");
                stage=Stage.SESSION_TIMEZONE;report(stage);statement.execute("SET SESSION time_zone='+09:00'");
                stage=Stage.NATIVE_PROOF;report(stage);
                try(var result=statement.executeQuery("SELECT @@session.transaction_read_only")) {
                    if(!result.next() || result.getInt(1)!=1)throw new SQLException("Native read-only session required");
                }
            }
            stage=Stage.NATIVE_CONFIRMED;report(stage);
            return guarded(raw);
        } catch(SQLException | RuntimeException failure) {
            if(raw!=null)try{raw.close();}catch(SQLException ignored){ }
            slots.release();throw new InitializationFailure(stage,reason(failure));
        }
    }
    @Override public Connection getConnection(String user,String password) throws SQLException {throw denied();}
    void verifyConnection() throws SQLException {try(var connection=getConnection()){ /* Native session already checked. */ }}
    void verifyConnection(Consumer<Stage> diagnostic) throws SQLException {
        startupDiagnostic.set(diagnostic);try{verifyConnection();}finally{startupDiagnostic.remove();}
    }
    private void report(Stage stage) {var diagnostic=startupDiagnostic.get();if(diagnostic!=null)diagnostic.accept(stage);}
    private static Reason reason(Exception failure) {
        String state=failure instanceof SQLException sql?sql.getSQLState():null;
        if(state==null)return Reason.UNAVAILABLE;
        if(state.startsWith("08"))return Reason.CONNECTIVITY;
        if(state.startsWith("28"))return Reason.AUTHENTICATION;
        if(state.startsWith("42"))return Reason.SQL_CONFIGURATION;
        return Reason.DRIVER_OR_SESSION;
    }
    private Connection guarded(Connection raw) {
        var closed=new java.util.concurrent.atomic.AtomicBoolean();
        InvocationHandler handle=(proxy,method,args)->{
            String name=method.getName();
            if(name.equals("close")) {if(closed.compareAndSet(false,true))try{raw.close();}finally{slots.release();}return null;}
            if(name.equals("isClosed"))return closed.get() || raw.isClosed();
            if(name.equals("prepareStatement")) {
                if(args==null || !(args[0] instanceof String sql) || !permits(sql))throw denied();
                var statement=(PreparedStatement)invoke(raw,method,args);statement.setQueryTimeout(3);
                return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                    String operation=m.getName();if(operation.equals("getConnection"))return proxy;
                    if(operation.equals("unwrap") || operation.equals("isWrapperFor") || operation.equals("execute")
                            || operation.startsWith("executeUpdate") || operation.startsWith("executeLarge")
                            || operation.equals("executeBatch") || operation.equals("addBatch")
                            || (operation.equals("executeQuery") && a!=null && a.length!=0)
                            || operation.equals("setQueryTimeout"))throw denied();
                    return invoke(statement,m,a);
                });
            }
            if(Set.of("createStatement","prepareCall","unwrap","isWrapperFor","getMetaData","setAutoCommit","commit","setCatalog","setSchema").contains(name)
                    || (name.equals("setReadOnly") && Boolean.FALSE.equals(args[0])))throw denied();
            return invoke(raw,method,args);
        };
        return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},handle);
    }
    private static Object invoke(Object target,Method method,Object[] args) throws Throwable {
        try {return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}
    }
    static boolean permits(String sql) {return sql!=null && ALLOWED.contains(normalized(sql));}
    private static String normalized(String sql) {return sql.replaceAll("\\s+"," ").trim();}
    private static SQLException denied() {return new SQLFeatureNotSupportedException("Read-only preview operation denied");}
}
