package com.yunye.mncdc.cdc;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.github.shyiko.mysql.binlog.event.XidEventData;
import com.yunye.mncdc.checkpoint.CheckpointStore;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.metadata.TableMetadataService;
import com.yunye.mncdc.model.BinlogCheckpoint;
import com.yunye.mncdc.model.CdcTransactionEvent;
import com.yunye.mncdc.model.CdcTransactionRow;
import com.yunye.mncdc.model.QualifiedTable;
import com.yunye.mncdc.model.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BinlogCdcLifecycleTransactionTest {

    @Mock
    private MiniCdcProperties properties;

    @Mock
    private MiniCdcProperties.Checkpoint checkpointProperties;

    @Mock
    private MiniCdcProperties.Mysql mysqlProperties;

    @Mock
    private TableMetadataService tableMetadataService;

    @Mock
    private CdcEventPublisher publisher;

    @Mock
    private CheckpointStore checkpointStore;

    @Mock
    private BinaryLogClient binaryLogClient;

    private BinlogCdcLifecycle lifecycle;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(properties.getCheckpoint()).thenReturn(checkpointProperties);
        lenient().when(properties.getMysql()).thenReturn(mysqlProperties);
        lenient().when(checkpointProperties.getConnectorName()).thenReturn("mini-user-sync");
        lenient().when(checkpointProperties.isEnabled()).thenReturn(true);
        lenient().when(mysqlProperties.resolvedTables()).thenReturn(List.of(new QualifiedTable("mini", "user")));
        lenient().when(binaryLogClient.getBinlogFilename()).thenReturn("mysql-bin.000001");

        lifecycle = new BinlogCdcLifecycle(properties, tableMetadataService, publisher, checkpointStore);
        setField("client", binaryLogClient);
        setField("tableMetadata", new TableMetadata(
                "mini",
                "user",
                List.of("id", "username"),
                List.of("id")
        ));
        AtomicBoolean running = (AtomicBoolean) getField("running");
        running.set(true);
    }

    @Test
    void startRejectsMultiTableConfigurationForCurrentSingleTableLifecycle() {
        QualifiedTable first = new QualifiedTable("mini", "user");
        QualifiedTable second = new QualifiedTable("mini", "order");
        when(mysqlProperties.resolvedTables()).thenReturn(List.of(first, second));
        when(tableMetadataService.getConfiguredTableMetadata()).thenReturn(Map.of(
                first, new TableMetadata("mini", "user", List.of("id"), List.of("id")),
                second, new TableMetadata("mini", "order", List.of("id"), List.of("id"))
        ));

        BinlogCdcLifecycle startLifecycle = new BinlogCdcLifecycle(properties, tableMetadataService, publisher, checkpointStore);

        assertThatThrownBy(startLifecycle::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Current lifecycle path supports only one configured table.");
        assertThat(startLifecycle.isRunning()).isFalse();
        verifyNoInteractions(checkpointStore, publisher);
    }

    @Test
    void publishesOneKafkaMessageAndCheckpointOnlyWhenXidArrives() throws Exception {
        when(publisher.publishTransaction(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        invokeHandleEvent(tableMapEvent(10L, "mini", "user"));
        invokeHandleEvent(writeRowsEvent(10L, 120L, new Serializable[]{1L, "alice"}));

        verify(publisher, never()).publishTransaction(any());
        verify(checkpointStore, never()).save(any());

        invokeHandleEvent(xidEvent(345L, 240L));

        ArgumentCaptor<CdcTransactionEvent> eventCaptor = ArgumentCaptor.forClass(CdcTransactionEvent.class);
        verify(publisher).publishTransaction(eventCaptor.capture());
        verify(checkpointStore).save(new BinlogCheckpoint(
                "mini-user-sync",
                "mini",
                "user",
                "mysql-bin.000001",
                240L
        ));

        CdcTransactionEvent transactionEvent = eventCaptor.getValue();
        assertThat(transactionEvent.transactionId()).isEqualTo("mini-user-sync:mysql-bin.000001:345:240");
        assertThat(transactionEvent.events()).hasSize(1);
        assertThat(transactionEvent.events().get(0).eventType()).isEqualTo("INSERT");
        assertThat(transactionEvent.events().get(0).primaryKey()).containsEntry("id", 1L);
        assertThat(transactionEvent.events().get(0).after()).containsEntry("username", "alice");
    }

    @Test
    void capturesBeforeAndAfterForUpdateRows() throws Exception {
        when(publisher.publishTransaction(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        invokeHandleEvent(tableMapEvent(10L, "mini", "user"));
        invokeHandleEvent(updateRowsEvent(10L, 120L, new Serializable[]{1L, "alice"}, new Serializable[]{1L, "alicia"}));
        invokeHandleEvent(xidEvent(345L, 240L));

        ArgumentCaptor<CdcTransactionEvent> eventCaptor = ArgumentCaptor.forClass(CdcTransactionEvent.class);
        verify(publisher).publishTransaction(eventCaptor.capture());

        CdcTransactionEvent transactionEvent = eventCaptor.getValue();
        assertThat(transactionEvent.events()).hasSize(1);
        CdcTransactionRow row = transactionEvent.events().get(0);
        assertThat(row.eventType()).isEqualTo("UPDATE");
        assertThat(row.primaryKey()).containsEntry("id", 1L);
        assertThat(row.before()).containsEntry("id", 1L);
        assertThat(row.before()).containsEntry("username", "alice");
        assertThat(row.after()).containsEntry("username", "alicia");
    }

    @Test
    void assignsEventIndexInCommittedRowOrder() throws Exception {
        when(publisher.publishTransaction(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        invokeHandleEvent(tableMapEvent(10L, "mini", "user"));
        invokeHandleEvent(writeRowsEvent(10L, 120L, new Serializable[]{1L, "alice"}));
        invokeHandleEvent(updateRowsEvent(10L, 120L, new Serializable[]{1L, "alice"}, new Serializable[]{1L, "alicia"}));
        invokeHandleEvent(deleteRowsEvent(10L, 120L, new Serializable[]{1L, "alicia"}));
        invokeHandleEvent(xidEvent(345L, 240L));

        ArgumentCaptor<CdcTransactionEvent> eventCaptor = ArgumentCaptor.forClass(CdcTransactionEvent.class);
        verify(publisher).publishTransaction(eventCaptor.capture());
        assertThat(eventCaptor.getValue().events())
                .extracting(CdcTransactionRow::eventIndex, CdcTransactionRow::eventType)
                .containsExactly(
                        tuple(0, "INSERT"),
                        tuple(1, "UPDATE"),
                        tuple(2, "DELETE")
                );
    }

    @Test
    void stopsListenerWhenUpdateMutatesPrimaryKey() throws Exception {
        invokeHandleEvent(tableMapEvent(10L, "mini", "user"));
        invokeHandleEvent(updateRowsEvent(10L, 120L, new Serializable[]{1L, "alice"}, new Serializable[]{2L, "alicia"}));

        assertThat(lifecycle.isRunning()).isFalse();
        verify(publisher, never()).publishTransaction(any());
        verify(checkpointStore, never()).save(any());
    }

    @Test
    void capturesBeforeAndNullAfterForDeleteRows() throws Exception {
        when(publisher.publishTransaction(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        invokeHandleEvent(tableMapEvent(10L, "mini", "user"));
        invokeHandleEvent(deleteRowsEvent(10L, 120L, new Serializable[]{1L, "alice"}));
        invokeHandleEvent(xidEvent(345L, 240L));

        ArgumentCaptor<CdcTransactionEvent> eventCaptor = ArgumentCaptor.forClass(CdcTransactionEvent.class);
        verify(publisher).publishTransaction(eventCaptor.capture());

        CdcTransactionEvent transactionEvent = eventCaptor.getValue();
        assertThat(transactionEvent.events()).hasSize(1);
        CdcTransactionRow row = transactionEvent.events().get(0);
        assertThat(row.eventType()).isEqualTo("DELETE");
        assertThat(row.primaryKey()).containsEntry("id", 1L);
        assertThat(row.before()).containsEntry("username", "alice");
        assertThat(row.after()).isNull();
    }

    @Test
    void stopsListenerWhenTransactionPublishFailsAfterRetryExhaustion() throws Exception {
        when(publisher.publishTransaction(any())).thenThrow(new IllegalStateException("publish failed"));

        invokeHandleEvent(tableMapEvent(10L, "mini", "user"));
        invokeHandleEvent(writeRowsEvent(10L, 120L, new Serializable[]{1L, "alice"}));
        invokeHandleEvent(xidEvent(345L, 240L));

        assertThat(lifecycle.isRunning()).isFalse();
        verify(checkpointStore, never()).save(any());
    }

    private void invokeHandleEvent(Event event) throws Exception {
        Method method = BinlogCdcLifecycle.class.getDeclaredMethod("handleEvent", Event.class);
        method.setAccessible(true);
        method.invoke(lifecycle, event);
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field field = BinlogCdcLifecycle.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(lifecycle, value);
    }

    private Object getField(String fieldName) throws Exception {
        Field field = BinlogCdcLifecycle.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(lifecycle);
    }

    private Event tableMapEvent(long tableId, String database, String table) {
        TableMapEventData data = new TableMapEventData();
        data.setTableId(tableId);
        data.setDatabase(database);
        data.setTable(table);
        return new Event(header(EventType.TABLE_MAP, 0L), data);
    }

    private Event writeRowsEvent(long tableId, long nextPosition, Serializable[] row) {
        WriteRowsEventData data = new WriteRowsEventData();
        data.setTableId(tableId);
        data.setRows(Collections.singletonList(row));
        return new Event(header(EventType.WRITE_ROWS, nextPosition), data);
    }

    private Event updateRowsEvent(long tableId, long nextPosition, Serializable[] before, Serializable[] after) {
        UpdateRowsEventData data = new UpdateRowsEventData();
        data.setTableId(tableId);
        data.setRows(Collections.singletonList(new AbstractMap.SimpleEntry<>(before, after)));
        return new Event(header(EventType.UPDATE_ROWS, nextPosition), data);
    }

    private Event deleteRowsEvent(long tableId, long nextPosition, Serializable[] row) {
        DeleteRowsEventData data = new DeleteRowsEventData();
        data.setTableId(tableId);
        data.setRows(Collections.singletonList(row));
        return new Event(header(EventType.DELETE_ROWS, nextPosition), data);
    }

    private Event xidEvent(long xid, long nextPosition) {
        XidEventData data = new XidEventData();
        data.setXid(xid);
        return new Event(header(EventType.XID, nextPosition), data);
    }

    private EventHeaderV4 header(EventType eventType, long nextPosition) {
        EventHeaderV4 header = new EventHeaderV4();
        header.setEventType(eventType);
        header.setNextPosition(nextPosition);
        header.setTimestamp(System.currentTimeMillis());
        return header;
    }
}
