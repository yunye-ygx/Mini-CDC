package com.yunye.mncdc.checkpoint;

import com.yunye.mncdc.entity.FullSyncTaskEntity;
import com.yunye.mncdc.mapper.FullSyncTaskMapper;
import com.yunye.mncdc.model.FullSyncTask;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FullSyncTaskStore {

    private final FullSyncTaskMapper fullSyncTaskMapper;

    public void start(FullSyncTask task) {
        try {
            fullSyncTaskMapper.insertOrUpdate(toEntity(task));
            assertTaskPersisted(task.connectorName(), task.databaseName(), task.tableName());
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist full sync task.", exception);
        }
    }

    public void markCompleted(String connectorName, String databaseName, String tableName) {
        try {
            assertTaskUpdated(
                    connectorName,
                    databaseName,
                    tableName,
                    fullSyncTaskMapper.markCompleted(connectorName, databaseName, tableName)
            );
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist full sync task.", exception);
        }
    }

    public void updateLastSentPk(String connectorName, String databaseName, String tableName, String lastSentPk) {
        try {
            assertTaskUpdated(
                    connectorName,
                    databaseName,
                    tableName,
                    fullSyncTaskMapper.updateLastSentPk(connectorName, databaseName, tableName, lastSentPk)
            );
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist full sync task.", exception);
        }
    }

    public void markFailed(String connectorName, String databaseName, String tableName, String lastError) {
        try {
            assertTaskUpdated(
                    connectorName,
                    databaseName,
                    tableName,
                    fullSyncTaskMapper.markFailed(connectorName, databaseName, tableName, lastError)
            );
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist full sync task.", exception);
        }
    }

    public List<FullSyncTask> loadRecent(int limit) {
        try {
            return fullSyncTaskMapper.selectRecent(limit).stream()
                    .map(this::toModel)
                    .toList();
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to load full sync tasks.", exception);
        }
    }

    private FullSyncTaskEntity toEntity(FullSyncTask task) {
        FullSyncTaskEntity entity = new FullSyncTaskEntity();
        entity.setConnectorName(task.connectorName());
        entity.setDatabaseName(task.databaseName());
        entity.setTableName(task.tableName());
        entity.setStatus(task.status().name());
        entity.setCutoverBinlogFilename(task.cutoverBinlogFilename());
        entity.setCutoverBinlogPosition(task.cutoverBinlogPosition());
        entity.setLastSentPk(task.lastSentPk());
        entity.setStartedAt(LocalDateTime.now());
        entity.setFinishedAt(task.finishedAt());
        entity.setLastError(task.lastError());
        return entity;
    }

    private FullSyncTask toModel(FullSyncTaskEntity entity) {
        return new FullSyncTask(
                entity.getConnectorName(),
                entity.getDatabaseName(),
                entity.getTableName(),
                entity.getStatus() == null ? null : com.yunye.mncdc.model.FullSyncTaskStatus.valueOf(entity.getStatus()),
                entity.getCutoverBinlogFilename(),
                entity.getCutoverBinlogPosition(),
                entity.getLastSentPk(),
                entity.getFinishedAt(),
                entity.getLastError()
        );
    }

    private void assertTaskUpdated(String connectorName, String databaseName, String tableName, int updatedRows) {
        if (updatedRows == 0) {
            throw new IllegalStateException(
                    "Full sync task row was not found for connector: "
                            + connectorName
                            + ", database: "
                            + databaseName
                            + ", table: "
                            + tableName
            );
        }
    }

    private void assertTaskPersisted(String connectorName, String databaseName, String tableName) {
        if (fullSyncTaskMapper.countByTaskKey(connectorName, databaseName, tableName) == 0) {
            throw new IllegalStateException(
                    "Full sync task row could not be persisted for connector: "
                            + connectorName
                            + ", database: "
                            + databaseName
                            + ", table: "
                            + tableName
                            + ". Expected full_sync_task to support one row per "
                            + "(connector_name, database_name, table_name). "
                            + "Apply the migration in src/main/sql/full-updat.sql and retry."
            );
        }
    }
}
