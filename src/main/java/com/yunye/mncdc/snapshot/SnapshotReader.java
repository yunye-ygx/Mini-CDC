package com.yunye.mncdc.snapshot;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Service
@RequiredArgsConstructor
public class SnapshotReader {

    private static final String CONSISTENT_SNAPSHOT_SQL = "START TRANSACTION WITH CONSISTENT SNAPSHOT";

    private final DataSource dataSource;

    public <T> T readConsistentSnapshot(SnapshotWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            startConsistentSnapshot(connection); //生成一致的快照

            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                rollbackQuietly(connection, exception);
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Failed to read MySQL snapshot.", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to read MySQL snapshot.", exception);
        }
    }

    private void startConsistentSnapshot(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(CONSISTENT_SNAPSHOT_SQL);
        }
    }

    private void rollbackQuietly(Connection connection, Exception originalException) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }

    @FunctionalInterface
    public interface SnapshotWork<T> {
        T execute(Connection connection) throws Exception;
    }
}
