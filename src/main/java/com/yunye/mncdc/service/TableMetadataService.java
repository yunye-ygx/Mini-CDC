package com.yunye.mncdc.service;

import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.mapper.TableMetadataMapper;
import com.yunye.mncdc.model.TableMetadata;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class TableMetadataService {

    private final MiniCdcProperties properties;
    private final TableMetadataMapper tableMetadataMapper;

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
        try {
            List<String> columns = tableMetadataMapper.selectColumnNames(mysql.getDatabase(), mysql.getTable());
            List<String> primaryKeys = tableMetadataMapper.selectPrimaryKeyNames(mysql.getDatabase(), mysql.getTable());
            if (columns.isEmpty()) {
                throw new IllegalStateException("No columns found for configured table.");
            }
            if (primaryKeys.isEmpty()) {
                throw new IllegalStateException("No primary key found for configured table.");
            }
            return new TableMetadata(mysql.getDatabase(), mysql.getTable(), columns, primaryKeys);
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to load table metadata from MySQL.", exception);
        }
    }
}
