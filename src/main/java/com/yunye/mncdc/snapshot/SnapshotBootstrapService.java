package com.yunye.mncdc.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunye.mncdc.cdc.CdcEventPublisher;
import com.yunye.mncdc.checkpoint.CheckpointStore;
import com.yunye.mncdc.checkpoint.FullSyncTaskStore;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.model.BinlogCheckpoint;
import com.yunye.mncdc.model.CdcTransactionEvent;
import com.yunye.mncdc.model.CdcTransactionRow;
import com.yunye.mncdc.model.FullSyncTask;
import com.yunye.mncdc.model.FullSyncTaskStatus;
import com.yunye.mncdc.model.SnapshotPage;
import com.yunye.mncdc.model.TableMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Service
@RequiredArgsConstructor
public class SnapshotBootstrapService {

    private final CheckpointStore checkpointStore;
    private final FullSyncTaskStore fullSyncTaskStore;
    private final SnapshotReader snapshotReader;
    private final CdcEventPublisher cdcEventPublisher;
    private final ObjectMapper objectMapper;
    private final MiniCdcProperties properties;

    public BinlogCheckpoint bootstrap(TableMetadata tableMetadata) {
        BinlogCheckpoint cutoverCheckpoint = checkpointStore.loadLatestServerCheckpoint();
        fullSyncTaskStore.start(new FullSyncTask(
                cutoverCheckpoint.connectorName(),
                tableMetadata.database(),
                tableMetadata.table(),
                FullSyncTaskStatus.RUNNING,
                cutoverCheckpoint.binlogFilename(),
                cutoverCheckpoint.binlogPosition(),
                null,
                null,
                null
        ));

        try {
            snapshotReader.readConsistentSnapshot(connection -> {
                publishSnapshotPages(connection, tableMetadata, cutoverCheckpoint);
                return cutoverCheckpoint;
            });
            fullSyncTaskStore.markCompleted(cutoverCheckpoint.connectorName());
            checkpointStore.save(cutoverCheckpoint);
            return cutoverCheckpoint;
        } catch (RuntimeException exception) {
            fullSyncTaskStore.markFailed(cutoverCheckpoint.connectorName(), failureMessage(exception));
            throw exception;
        }
    }

    protected void publishSnapshotPages(
            Connection connection,
            TableMetadata tableMetadata,
            BinlogCheckpoint cutoverCheckpoint
    ) throws SQLException {
        Map<String, Object> lastPrimaryKey = null;
        int pageIndex = 0;
        while (true) {
            SnapshotPage page = readPage(connection, tableMetadata, lastPrimaryKey, properties.getSnapshot().getPageSize());
            if (page.rows().isEmpty()) {
                return;
            }

            CdcTransactionEvent snapshotEvent = buildSnapshotEvent(tableMetadata, cutoverCheckpoint, pageIndex, page.rows());
            publishSnapshotPage(snapshotEvent);
            fullSyncTaskStore.updateLastSentPk(
                    cutoverCheckpoint.connectorName(),
                    serializePrimaryKey(orderedPrimaryKey(tableMetadata, page.lastPrimaryKey()))
            );

            if (!page.hasMore()) {
                return;
            }
            lastPrimaryKey = page.lastPrimaryKey();
            pageIndex++;
        }
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

    protected CdcTransactionEvent buildSnapshotEvent(
            TableMetadata tableMetadata,
            BinlogCheckpoint cutoverCheckpoint,
            int pageIndex,
            List<Map<String, Object>> rows
    ) {
        List<CdcTransactionRow> eventRows = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            eventRows.add(new CdcTransactionRow(
                    tableMetadata.database(),
                    tableMetadata.table(),
                    i,
                    "SNAPSHOT_UPSERT",
                    extractPrimaryKey(row, tableMetadata),
                    null,
                    Collections.unmodifiableMap(new LinkedHashMap<>(row))
            ));
        }
        return new CdcTransactionEvent(
                buildTransactionId(cutoverCheckpoint, pageIndex),
                cutoverCheckpoint.connectorName(),
                cutoverCheckpoint.binlogFilename(),
                pageIndex,
                cutoverCheckpoint.binlogPosition(),
                System.currentTimeMillis(),
                List.copyOf(eventRows)
        );
    }

    protected String buildTransactionId(BinlogCheckpoint cutoverCheckpoint, int pageIndex) {
        return "snapshot:"
                + cutoverCheckpoint.connectorName()
                + ":"
                + cutoverCheckpoint.binlogFilename()
                + ":"
                + cutoverCheckpoint.binlogPosition()
                + ":"
                + pageIndex;
    }

    private void publishSnapshotPage(CdcTransactionEvent snapshotEvent) {
        try {
            cdcEventPublisher.publishSnapshotPage(snapshotEvent).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing snapshot page.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            throw new IllegalStateException("Failed to publish snapshot page.", cause);
        }
    }

    private String serializePrimaryKey(Map<String, Object> primaryKey) {
        try {
            return objectMapper.writeValueAsString(primaryKey);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize snapshot progress.", exception);
        }
    }

    private String failureMessage(RuntimeException exception) {
        String message = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getName();
        Throwable rootCause = rootCause(exception);
        String rootCauseMessage = rootCause.getMessage();
        if (rootCause == exception || rootCauseMessage == null || rootCauseMessage.isBlank() || message.equals(rootCauseMessage)) {
            return message;
        }
        return message + ": " + rootCauseMessage;
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
        return orderedPrimaryKey(tableMetadata, row);
    }

    private Map<String, Object> orderedPrimaryKey(TableMetadata tableMetadata, Map<String, Object> source) {
        LinkedHashMap<String, Object> primaryKey = new LinkedHashMap<>();
        for (String primaryKeyColumn : tableMetadata.primaryKeys()) {
            primaryKey.put(primaryKeyColumn, source.get(primaryKeyColumn));
        }
        return Collections.unmodifiableMap(primaryKey);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
