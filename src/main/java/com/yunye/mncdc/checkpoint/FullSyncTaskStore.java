package com.yunye.mncdc.checkpoint;

import com.yunye.mncdc.entity.FullSyncTaskEntity;
import com.yunye.mncdc.mapper.FullSyncTaskMapper;
import com.yunye.mncdc.model.FullSyncTask;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class FullSyncTaskStore {

    private final FullSyncTaskMapper fullSyncTaskMapper;

    public void start(FullSyncTask task) {
        try {
            fullSyncTaskMapper.insertOrUpdate(toEntity(task));
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist full sync task.", exception);
        }
    }

    public void markCompleted(String connectorName) {
        try {
            assertTaskUpdated(connectorName, fullSyncTaskMapper.markCompleted(connectorName));
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist full sync task.", exception);
        }
    }

    public void updateLastSentPk(String connectorName, String lastSentPk) {
        try {
            assertTaskUpdated(connectorName, fullSyncTaskMapper.updateLastSentPk(connectorName, lastSentPk));
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist full sync task.", exception);
        }
    }

    public void markFailed(String connectorName, String lastError) {
        try {
            assertTaskUpdated(connectorName, fullSyncTaskMapper.markFailed(connectorName, lastError));
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist full sync task.", exception);
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

    private void assertTaskUpdated(String connectorName, int updatedRows) {
        if (updatedRows == 0) {
            throw new IllegalStateException("Full sync task row was not found for connector: " + connectorName);
        }
    }
}
