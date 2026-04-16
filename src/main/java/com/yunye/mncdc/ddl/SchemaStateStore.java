package com.yunye.mncdc.ddl;

import com.yunye.mncdc.entity.SchemaStateEntity;
import com.yunye.mncdc.mapper.SchemaStateMapper;
import com.yunye.mncdc.model.SchemaState;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SchemaStateStore {

    private final SchemaStateMapper schemaStateMapper;

    public void upsert(SchemaState state) {
        try {
            schemaStateMapper.insertOrUpdate(toEntity(state));
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist schema state.", exception);
        }
    }

    public boolean anyNonActive(Collection<String> tableKeys) {
        return loadStates(tableKeys).stream().anyMatch(state -> !"ACTIVE".equals(state.status()));
    }

    public SchemaVersion currentBlockingVersion(Collection<String> tableKeys) {
        return loadStates(tableKeys).stream()
                .filter(state -> !"ACTIVE".equals(state.status()))
                .max(Comparator
                        .comparing(SchemaState::schemaBinlogFile, Comparator.nullsLast(String::compareTo))
                        .thenComparing(SchemaState::schemaNextPosition, Comparator.nullsLast(Long::compareTo)))
                .map(state -> new SchemaVersion(state.schemaBinlogFile(), state.schemaNextPosition()))
                .orElse(null);
    }

    public SchemaState load(String databaseName, String tableName) {
        return loadStates(List.of(databaseName + "." + tableName)).stream().findFirst().orElse(null);
    }

    public void markRebuilding(String databaseName, String tableName, String schemaBinlogFile, Long schemaNextPosition) {
        upsert(withStatus(databaseName, tableName, "REBUILDING", schemaBinlogFile, schemaNextPosition));
    }

    public void markRebuildRequired(String databaseName, String tableName, String schemaBinlogFile, Long schemaNextPosition) {
        upsert(withStatus(databaseName, tableName, "REBUILD_REQUIRED", schemaBinlogFile, schemaNextPosition));
    }

    public void markRebuildFailed(String databaseName, String tableName, String schemaBinlogFile, Long schemaNextPosition) {
        upsert(withStatus(databaseName, tableName, "REBUILD_FAILED", schemaBinlogFile, schemaNextPosition));
    }

    public void markActive(String databaseName, String tableName, String schemaBinlogFile, Long schemaNextPosition) {
        upsert(withStatus(databaseName, tableName, "ACTIVE", schemaBinlogFile, schemaNextPosition));
    }

    public record SchemaVersion(String binlogFilename, Long nextPosition) {
    }

    private SchemaStateEntity toEntity(SchemaState state) {
        SchemaStateEntity entity = new SchemaStateEntity();
        entity.setTableKey(state.tableKey());
        entity.setDatabaseName(state.databaseName());
        entity.setTableName(state.tableName());
        entity.setStatus(state.status());
        entity.setSchemaBinlogFile(state.schemaBinlogFile());
        entity.setSchemaNextPosition(state.schemaNextPosition());
        entity.setDdlType(state.ddlType());
        entity.setDdlSql(state.ddlSql());
        return entity;
    }

    private List<SchemaState> loadStates(Collection<String> tableKeys) {
        if (tableKeys == null || tableKeys.isEmpty()) {
            return List.of();
        }
        try {
            return schemaStateMapper.selectByTableKeys(List.copyOf(tableKeys)).stream()
                    .filter(Objects::nonNull)
                    .map(this::toModel)
                    .toList();
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to load schema state.", exception);
        }
    }

    private SchemaState toModel(SchemaStateEntity entity) {
        return new SchemaState(
                entity.getDatabaseName(),
                entity.getTableName(),
                entity.getStatus(),
                entity.getSchemaBinlogFile(),
                entity.getSchemaNextPosition(),
                entity.getDdlType(),
                entity.getDdlSql()
        );
    }

    private SchemaState withStatus(
            String databaseName,
            String tableName,
            String status,
            String schemaBinlogFile,
            Long schemaNextPosition
    ) {
        SchemaState current = load(databaseName, tableName);
        return new SchemaState(
                databaseName,
                tableName,
                status,
                schemaBinlogFile,
                schemaNextPosition,
                current == null ? null : current.ddlType(),
                current == null ? null : current.ddlSql()
        );
    }
}
