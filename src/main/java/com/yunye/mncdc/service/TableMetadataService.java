package com.yunye.mncdc.service;

import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.model.TableMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class TableMetadataService {

    private static final String COLUMN_SQL = """
            SELECT COLUMN_NAME
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = ?
              AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
            """;

    private final MiniCdcProperties properties;

    private final AtomicReference<TableMetadata> cache = new AtomicReference<>();

    public TableMetadata getConfiguredTableMetadata() {
        TableMetadata cached = cache.get();
        if (cached != null) {
            return cached;
        }
        TableMetadata loaded = loadMetadata();
        cache.compareAndSet(null, loaded);
        return cache.get();
    }

    private TableMetadata loadMetadata() {
        MiniCdcProperties.Mysql mysql = properties.getMysql();
        try (Connection connection = DriverManager.getConnection(
                mysql.getJdbcUrl(),
                mysql.getUsername(),
                mysql.getPassword()
        )) {
            List<String> columns = loadColumns(connection, mysql.getDatabase(), mysql.getTable());
            List<String> primaryKeys = loadPrimaryKeys(connection, mysql.getDatabase(), mysql.getTable());
            if (columns.isEmpty()) {
                throw new IllegalStateException("No columns found for configured table.");
            }
            if (primaryKeys.isEmpty()) {
                throw new IllegalStateException("No primary key found for configured table.");
            }
            return new TableMetadata(mysql.getDatabase(), mysql.getTable(), columns, primaryKeys);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load table metadata from MySQL.", exception);
        }
    }

    private List<String> loadColumns(Connection connection, String database, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(COLUMN_SQL)) {
            statement.setString(1, database);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString("COLUMN_NAME"));
                }
            }
        }
        return columns;
    }

    private List<String> loadPrimaryKeys(Connection connection, String database, String table) throws SQLException {
        List<PrimaryKeyColumn> primaryKeys = new ArrayList<>();
        try (ResultSet resultSet = connection.getMetaData().getPrimaryKeys(database, null, table)) {
            while (resultSet.next()) {
                primaryKeys.add(new PrimaryKeyColumn(
                        resultSet.getShort("KEY_SEQ"),
                        resultSet.getString("COLUMN_NAME")
                ));
            }
        }
        primaryKeys.sort(Comparator.comparingInt(PrimaryKeyColumn::order));
        return primaryKeys.stream().map(PrimaryKeyColumn::name).toList();
    }

    private record PrimaryKeyColumn(int order, String name) {
    }
}
