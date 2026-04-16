package com.yunye.mncdc.ddl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.metadata.TableMetadataService;
import com.yunye.mncdc.model.QualifiedTable;
import com.yunye.mncdc.model.RebuildTask;
import com.yunye.mncdc.model.RedisRowMetadata;
import com.yunye.mncdc.model.RedisRowVersion;
import com.yunye.mncdc.model.SnapshotPage;
import com.yunye.mncdc.model.TableMetadata;
import com.yunye.mncdc.snapshot.SnapshotQueryBuilder;
import com.yunye.mncdc.snapshot.SnapshotReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

@Slf4j
@Service
public class RedisStagingRebuildService {

    private final SnapshotReader snapshotReader;
    private final TableMetadataService tableMetadataService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final MiniCdcProperties properties;

    @Autowired
    public RedisStagingRebuildService(
            SnapshotReader snapshotReader,
            TableMetadataService tableMetadataService,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            MiniCdcProperties properties
    ) {
        this.snapshotReader = snapshotReader;
        this.tableMetadataService = tableMetadataService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    RedisStagingRebuildService(
            TableMetadataService tableMetadataService,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            MiniCdcProperties properties
    ) {
        this(null, tableMetadataService, stringRedisTemplate, objectMapper, properties);
    }

    public void rebuildToStaging(RebuildTask task) {
        TableMetadata tableMetadata = loadCurrentTableMetadata(task);
        log.info("Rebuilding {}.{} into staging for {}:{}", task.databaseName(), task.tableName(), task.schemaBinlogFile(), task.schemaNextPosition());
        deleteByPattern(stagingNamespacePattern(task));  //清除之前的stag旧数据
        forEachSnapshotRow(tableMetadata, (pageIndex, rowIndex, row) -> writeSnapshotRow(task, tableMetadata, pageIndex, rowIndex, row));
    }

    public void publish(RebuildTask task) {
        log.info("Publishing staged rebuild for {}.{} at {}:{}", task.databaseName(), task.tableName(), task.schemaBinlogFile(), task.schemaNextPosition());
        replaceNamespace(stagingDataPrefix(task), liveDataPrefix(task)); //把stag的数据复制真正的key里面
        if (properties.getRedis().getApplyMode() == MiniCdcProperties.Redis.ApplyMode.META) {
            replaceNamespace(stagingMetaPrefix(task), liveMetaPrefix(task));
        }
        deleteByPattern(stagingNamespacePattern(task));
    }

    public void discard(RebuildTask task) {
        log.info("Discarding staged rebuild for {}.{} at {}:{}", task.databaseName(), task.tableName(), task.schemaBinlogFile(), task.schemaNextPosition());
        deleteByPattern(stagingNamespacePattern(task));
    }

    protected void forEachSnapshotRow(TableMetadata tableMetadata, SnapshotRowConsumer consumer) {
        if (snapshotReader == null) {
            throw new IllegalStateException("SnapshotReader is required for rebuild execution.");
        }
        snapshotReader.readConsistentSnapshot(connection -> {
            Map<String, Object> lastPrimaryKey = null;
            int pageIndex = 0;
            while (true) {
                SnapshotPage page = readPage(connection, tableMetadata, lastPrimaryKey, properties.getSnapshot().getPageSize());
                if (page.rows().isEmpty()) {
                    return null;
                }
                for (int rowIndex = 0; rowIndex < page.rows().size(); rowIndex++) {
                    consumer.accept(pageIndex, rowIndex, page.rows().get(rowIndex));
                }
                if (!page.hasMore()) {
                    return null;
                }
                lastPrimaryKey = page.lastPrimaryKey();
                pageIndex++;
            }
        });
    }

    protected SnapshotPage readPage(
            Connection connection,
            TableMetadata tableMetadata,
            Map<String, Object> lastPrimaryKey,
            int pageSize
    ) throws SQLException {
        SnapshotQueryBuilder.QuerySpec querySpec = SnapshotQueryBuilder.build(tableMetadata, lastPrimaryKey, pageSize);
        try (PreparedStatement preparedStatement = connection.prepareStatement(querySpec.sql())) {
            bindParameters(preparedStatement, querySpec.parameters());
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                List<Map<String, Object>> rows = readRows(resultSet);
                if (rows.isEmpty()) {
                    return new SnapshotPage(List.of(), lastPrimaryKey == null ? Map.of() : lastPrimaryKey, false);
                }
                Map<String, Object> pageLastPrimaryKey = extractPrimaryKey(rows.get(rows.size() - 1), tableMetadata);
                return new SnapshotPage(rows, pageLastPrimaryKey, rows.size() == pageSize);
            }
        }
    }

    private TableMetadata loadCurrentTableMetadata(RebuildTask task) {
        Map<QualifiedTable, TableMetadata> metadataByTable = tableMetadataService.refreshConfiguredTableMetadata();
        TableMetadata tableMetadata = metadataByTable.get(new QualifiedTable(task.databaseName(), task.tableName()));
        if (tableMetadata == null) {
            throw new IllegalStateException(
                    "Configured table metadata is missing for rebuild task "
                            + task.databaseName()
                            + "."
                            + task.tableName()
            );
        }
        return tableMetadata;
    }

    private void writeSnapshotRow(
            RebuildTask task,
            TableMetadata tableMetadata,
            int pageIndex,
            int rowIndex,
            Map<String, Object> row
    ) {
        String primaryKeySuffix = buildPrimaryKeySuffix(tableMetadata, row);
        stringRedisTemplate.opsForValue().set(
                stagingDataPrefix(task) + primaryKeySuffix,
                toJson(row)
        );
        if (properties.getRedis().getApplyMode() == MiniCdcProperties.Redis.ApplyMode.META) {
            RedisRowMetadata metadata = new RedisRowMetadata(
                    false,
                    new RedisRowVersion(
                            task.schemaBinlogFile(),
                            task.schemaNextPosition(),
                            rowIndex,
                            "rebuild:" + task.taskId() + ":" + pageIndex
                    )
            );
            stringRedisTemplate.opsForValue().set(
                    stagingMetaPrefix(task) + primaryKeySuffix,
                    toJson(metadata)
            );
        }
    }

    private void replaceNamespace(String sourcePrefix, String targetPrefix) {
        deleteByPattern(targetPrefix + "*");
        Set<String> sourceKeys = stringRedisTemplate.keys(sourcePrefix + "*");
        if (sourceKeys == null || sourceKeys.isEmpty()) {
            return;
        }
        for (String sourceKey : sourceKeys) {
            String value = stringRedisTemplate.opsForValue().get(sourceKey);
            if (value != null) {
                stringRedisTemplate.opsForValue().set(targetPrefix + sourceKey.substring(sourcePrefix.length()), value);
            }
        }
    }

    private void deleteByPattern(String pattern) {
        Set<String> keys = stringRedisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    private String stagingNamespacePattern(RebuildTask task) {
        return stagingNamespace(task) + "*";
    }

    private String stagingDataPrefix(RebuildTask task) {
        return stagingNamespace(task) + "data:";
    }

    private String stagingMetaPrefix(RebuildTask task) {
        return stagingNamespace(task) + "meta:";
    }

    private String stagingNamespace(RebuildTask task) {
        return properties.getDdl().getStagingKeyPrefix()
                + task.databaseName()
                + "."
                + task.tableName()
                + ":"
                + task.schemaBinlogFile()
                + ":"
                + task.schemaNextPosition()
                + ":";
    }

    private String liveDataPrefix(RebuildTask task) {
        return properties.getRedis().getKeyPrefix() + tableQualifier(task) + ":";
    }

    private String liveMetaPrefix(RebuildTask task) {
        return properties.getRedis().getRowMetaPrefix() + tableQualifier(task) + ":";
    }
    private String tableQualifier(RebuildTask task) {
        return switch (properties.getRedis().getBusinessKeyScope()) {
            case TABLE -> task.tableName();
            case DATABASE_TABLE -> task.databaseName() + "." + task.tableName();
        };
    }

    private String buildPrimaryKeySuffix(TableMetadata tableMetadata, Map<String, Object> row) {
        StringJoiner joiner = new StringJoiner(":");
        for (String primaryKey : tableMetadata.primaryKeys()) {
            if (!row.containsKey(primaryKey)) {
                throw new IllegalStateException(
                        "Snapshot row for " + tableMetadata.database() + "." + tableMetadata.table()
                                + " is missing primary key column " + primaryKey
                );
            }
            joiner.add(String.valueOf(row.get(primaryKey)));
        }
        return joiner.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize rebuild payload.", exception);
        }
    }

    private void bindParameters(PreparedStatement preparedStatement, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            preparedStatement.setObject(i + 1, parameters.get(i));
        }
    }

    private List<Map<String, Object>> readRows(ResultSet resultSet) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        while (resultSet.next()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
                row.put(metadata.getColumnLabel(columnIndex), resultSet.getObject(columnIndex));
            }
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> extractPrimaryKey(Map<String, Object> row, TableMetadata tableMetadata) {
        LinkedHashMap<String, Object> primaryKey = new LinkedHashMap<>();
        for (String primaryKeyColumn : tableMetadata.primaryKeys()) {
            primaryKey.put(primaryKeyColumn, row.get(primaryKeyColumn));
        }
        return primaryKey;
    }

    @FunctionalInterface
    protected interface SnapshotRowConsumer {

        void accept(int pageIndex, int rowIndex, Map<String, Object> row);
    }
}
