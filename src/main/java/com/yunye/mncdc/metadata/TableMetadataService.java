package com.yunye.mncdc.metadata;

import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.mapper.TableMetadataMapper;
import com.yunye.mncdc.model.QualifiedTable;
import com.yunye.mncdc.model.TableMetadata;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class TableMetadataService {

    private final MiniCdcProperties properties;
    private final TableMetadataMapper tableMetadataMapper;

    private final AtomicReference<Map<QualifiedTable, TableMetadata>> cache = new AtomicReference<>();

    public Map<QualifiedTable, TableMetadata> getConfiguredTableMetadata() {
        Map<QualifiedTable, TableMetadata> cached = cache.get();
        if (cached != null) {
            return cached;
        }
        Map<QualifiedTable, TableMetadata> loaded = loadMetadata();
        cache.compareAndSet(null, loaded);
        return cache.get();
    }

    private Map<QualifiedTable, TableMetadata> loadMetadata() {
        List<QualifiedTable> configuredTables = properties.getMysql().resolvedTables();
        if (configuredTables.isEmpty()) {
            throw new IllegalStateException("At least one listened table must be configured.");
        }
        try {
            LinkedHashMap<QualifiedTable, TableMetadata> metadataByTable = new LinkedHashMap<>();
            for (QualifiedTable table : configuredTables) {
                List<String> columns = tableMetadataMapper.selectColumnNames(table.database(), table.table());
                List<String> primaryKeys = tableMetadataMapper.selectPrimaryKeyNames(table.database(), table.table());
                if (columns.isEmpty()) {
                    throw new IllegalStateException(
                            "No columns found for configured table " + table.database() + "." + table.table()
                    );
                }
                if (primaryKeys.isEmpty()) {
                    throw new IllegalStateException(
                            "No primary key found for configured table " + table.database() + "." + table.table()
                    );
                }
                metadataByTable.put(table, new TableMetadata(table.database(), table.table(), columns, primaryKeys));
            }
            return Collections.unmodifiableMap(metadataByTable);
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to load table metadata from MySQL.", exception);
        }
    }
}
