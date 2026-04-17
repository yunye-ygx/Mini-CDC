package com.yunye.mncdc.ddl;

import com.yunye.mncdc.entity.RebuildTaskEntity;
import com.yunye.mncdc.mapper.RebuildTaskMapper;
import com.yunye.mncdc.model.RebuildTask;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RebuildTaskStore {

    private final RebuildTaskMapper rebuildTaskMapper;

    public void create(RebuildTask task) {
        try {
            rebuildTaskMapper.insert(toEntity(task));
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist rebuild task.", exception);
        }
    }

    public Optional<RebuildTask> claimNextPendingTask() {
        try {
            RebuildTaskEntity entity = rebuildTaskMapper.selectFirstPending();
            if (entity == null) {
                return Optional.empty();
            }
            int updated = rebuildTaskMapper.transitionStatus(entity.getTaskId(), "PENDING", "RUNNING");
            if (updated == 0) {
                return Optional.empty();
            }
            entity.setStatus("RUNNING");
            return Optional.of(toModel(entity));
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to load rebuild task.", exception);
        }
    }

    public void markObsolete(String taskId) {
        updateStatus(taskId, "OBSOLETE");
    }

    public void markDone(String taskId) {
        updateStatus(taskId, "DONE");
    }

    public void requeue(String taskId, String lastError) {
        try {
            rebuildTaskMapper.requeue(taskId, lastError);
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist rebuild task.", exception);
        }
    }

    public void markFailed(String taskId, String lastError) {
        try {
            rebuildTaskMapper.markFailed(taskId, lastError);
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist rebuild task.", exception);
        }
    }

    public void resetExpiredRunningTasks(Instant cutoff) {
        try {
            rebuildTaskMapper.resetExpiredRunningTasks(Timestamp.from(cutoff));
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist rebuild task.", exception);
        }
    }

    public List<RebuildTask> loadRecent(String status, int limit) {
        try {
            return rebuildTaskMapper.selectRecent(status, limit).stream()
                    .map(this::toModel)
                    .toList();
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to load rebuild task.", exception);
        }
    }

    public long countByStatus(String status) {
        try {
            return rebuildTaskMapper.countByStatus(status);
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to count rebuild tasks.", exception);
        }
    }

    private RebuildTaskEntity toEntity(RebuildTask task) {
        RebuildTaskEntity entity = new RebuildTaskEntity();
        entity.setTaskId(task.taskId());
        entity.setDatabaseName(task.databaseName());
        entity.setTableName(task.tableName());
        entity.setSchemaBinlogFile(task.schemaBinlogFile());
        entity.setSchemaNextPosition(task.schemaNextPosition());
        entity.setStatus(task.status());
        entity.setRetryCount(task.retryCount());
        entity.setLastError(task.lastError());
        return entity;
    }

    private RebuildTask toModel(RebuildTaskEntity entity) {
        return new RebuildTask(
                entity.getTaskId(),
                entity.getDatabaseName(),
                entity.getTableName(),
                entity.getSchemaBinlogFile(),
                entity.getSchemaNextPosition(),
                entity.getStatus(),
                entity.getRetryCount(),
                entity.getLastError()
        );
    }

    private void updateStatus(String taskId, String status) {
        try {
            rebuildTaskMapper.updateStatus(taskId, status);
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist rebuild task.", exception);
        }
    }
}
