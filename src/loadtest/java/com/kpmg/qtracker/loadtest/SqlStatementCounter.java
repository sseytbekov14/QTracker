package com.kpmg.qtracker.loadtest;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Counts the SQL statements Hibernate prepares on the current thread (one request; SqlCountFilter reads and resets
 * it). Registered in application-loadtest.yml (hibernate.session_factory.statement_inspector); the statement is
 * left as it is. JdbcTemplate statements (the notification outbox) are not counted.
 */
public class SqlStatementCounter implements StatementInspector {

    private static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);

    @Override
    public String inspect(String sql) {
        COUNT.get()[0]++;
        return sql;
    }

    static void reset() {
        COUNT.get()[0] = 0;
    }

    static int count() {
        return COUNT.get()[0];
    }
}
