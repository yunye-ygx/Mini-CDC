package com.yunye.mncdc.metadata;

import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.mapper.TableMetadataMapper;
import com.yunye.mncdc.model.QualifiedTable;
import com.yunye.mncdc.model.TableMetadata;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataRetrievalFailureException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TableMetadataServiceTest {

    @Mock
    private MiniCdcProperties properties;

    @Mock
    private MiniCdcProperties.Mysql mysqlProperties;

    @Mock
    private TableMetadataMapper tableMetadataMapper;

    private TableMetadataService service;

    @BeforeEach
    void setUp() {
        when(properties.getMysql()).thenReturn(mysqlProperties);
        when(mysqlProperties.resolvedTables()).thenReturn(List.of(
                new QualifiedTable("mini", "user"),
                new QualifiedTable("mini", "order")
        ));
        service = new TableMetadataService(properties, tableMetadataMapper);
    }

    @Test
    void loadsMetadataForEveryConfiguredTable() {
        when(tableMetadataMapper.selectColumnNames("mini", "user")).thenReturn(List.of("id", "username"));
        when(tableMetadataMapper.selectPrimaryKeyNames("mini", "user")).thenReturn(List.of("id"));
        when(tableMetadataMapper.selectColumnNames("mini", "order")).thenReturn(List.of("id", "user_id", "amount"));
        when(tableMetadataMapper.selectPrimaryKeyNames("mini", "order")).thenReturn(List.of("id"));

        Map<QualifiedTable, TableMetadata> metadata = service.getConfiguredTableMetadata();

        assertThat(metadata).containsEntry(
                new QualifiedTable("mini", "user"),
                new TableMetadata("mini", "user", List.of("id", "username"), List.of("id"))
        );
        assertThat(metadata).containsEntry(
                new QualifiedTable("mini", "order"),
                new TableMetadata("mini", "order", List.of("id", "user_id", "amount"), List.of("id"))
        );
    }

    @Test
    void cachesPerTableMetadataAcrossRepeatedCalls() {
        when(tableMetadataMapper.selectColumnNames("mini", "user")).thenReturn(List.of("id", "username"));
        when(tableMetadataMapper.selectPrimaryKeyNames("mini", "user")).thenReturn(List.of("id"));
        when(tableMetadataMapper.selectColumnNames("mini", "order")).thenReturn(List.of("id", "user_id", "amount"));
        when(tableMetadataMapper.selectPrimaryKeyNames("mini", "order")).thenReturn(List.of("id"));

        service.getConfiguredTableMetadata();
        service.getConfiguredTableMetadata();

        verify(tableMetadataMapper, times(1)).selectColumnNames("mini", "user");
        verify(tableMetadataMapper, times(1)).selectPrimaryKeyNames("mini", "user");
        verify(tableMetadataMapper, times(1)).selectColumnNames("mini", "order");
        verify(tableMetadataMapper, times(1)).selectPrimaryKeyNames("mini", "order");
    }

    @Test
    void wrapsPersistenceExceptionsWhenLoadingMetadata() {
        PersistenceException cause = new PersistenceException("db error");
        when(tableMetadataMapper.selectColumnNames("mini", "user")).thenThrow(cause);

        assertThatThrownBy(() -> service.getConfiguredTableMetadata())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to load table metadata from MySQL.")
                .hasCause(cause);
    }

    @Test
    void wrapsDataAccessExceptionsWhenLoadingMetadata() {
        DataRetrievalFailureException cause = new DataRetrievalFailureException("db error");
        when(tableMetadataMapper.selectColumnNames("mini", "user")).thenThrow(cause);

        assertThatThrownBy(() -> service.getConfiguredTableMetadata())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to load table metadata from MySQL.")
                .hasCause(cause);
    }
}
