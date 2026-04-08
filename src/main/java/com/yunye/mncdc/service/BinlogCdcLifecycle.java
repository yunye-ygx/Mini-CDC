package com.yunye.mncdc.service;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.model.CdcEventMessage;
import com.yunye.mncdc.model.TableMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Serializable;
import java.sql.Timestamp;
import java.time.temporal.TemporalAccessor;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mini-cdc", name = "enabled", havingValue = "true")
public class BinlogCdcLifecycle implements SmartLifecycle {

    private final MiniCdcProperties properties;

    private final TableMetadataService tableMetadataService;

    private final CdcEventPublisher cdcEventPublisher;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final ExecutorService executorService = Executors.newSingleThreadExecutor(new NamedThreadFactory());

    private final Map<Long, QualifiedTable> tableMappings = new ConcurrentHashMap<>();

    private volatile BinaryLogClient client;

    private volatile TableMetadata tableMetadata;

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            tableMetadata = tableMetadataService.getConfiguredTableMetadata();
            client = createClient();
            executorService.submit(this::connectSafely);
            log.info("Mini CDC listener starting for {}.{}", tableMetadata.database(), tableMetadata.table());
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
    }

    @Override
    public void stop() {
        running.set(false);
        if (client != null) {
            try {
                client.disconnect();
            } catch (IOException exception) {
                log.warn("Failed to disconnect binlog client cleanly.", exception);
            }
        }
        executorService.shutdownNow();
        tableMappings.clear();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    private BinaryLogClient createClient() {
        MiniCdcProperties.Mysql mysql = properties.getMysql();
        BinaryLogClient binaryLogClient = new BinaryLogClient(
                mysql.getHost(),
                mysql.getPort(),
                mysql.getUsername(),
                mysql.getPassword()
        );
        binaryLogClient.setServerId(mysql.getServerId());
        binaryLogClient.setKeepAlive(true);
        binaryLogClient.registerEventListener(this::handleEvent);
        return binaryLogClient;
    }

    private void connectSafely() {
        try {
            client.connect();
        } catch (IOException exception) {
            running.set(false);
            log.error("Binlog listener stopped because MySQL connection failed.", exception);
        }
    }

    private void handleEvent(Event event) {
        EventType eventType = event.getHeader().getEventType();
        EventData eventData = event.getData();
        if (eventType == EventType.TABLE_MAP && eventData instanceof TableMapEventData tableMap) {
            tableMappings.put(tableMap.getTableId(), new QualifiedTable(tableMap.getDatabase(), tableMap.getTable()));
            return;
        }
        if (eventData == null) {
            return;
        }
        try {
            if (eventType == EventType.EXT_WRITE_ROWS || eventType == EventType.WRITE_ROWS) {
                handleInsert((WriteRowsEventData) eventData);
            } else if (eventType == EventType.EXT_UPDATE_ROWS || eventType == EventType.UPDATE_ROWS) {
                handleUpdate((UpdateRowsEventData) eventData);
            }
        } catch (Exception exception) {
            log.error("Failed to process binlog event {}", eventType, exception);
        }
    }

    private void handleInsert(WriteRowsEventData eventData) {
        if (!isConfiguredTable(eventData.getTableId())) {
            return;
        }
        for (Serializable[] row : eventData.getRows()) {
            Map<String, Object> after = toRowMap(row);
            Map<String, Object> primaryKey = extractPrimaryKey(after);
            CdcEventMessage message = CdcEventMessage.builder()
                    .database(tableMetadata.database())
                    .table(tableMetadata.table())
                    .eventType("INSERT")
                    .primaryKey(primaryKey)
                    .before(null)
                    .after(after)
                    .timestamp(System.currentTimeMillis())
                    .build();
            cdcEventPublisher.publish(message);
        }
    }

    private void handleUpdate(UpdateRowsEventData eventData) {
        if (!isConfiguredTable(eventData.getTableId())) {
            return;
        }
        for (Map.Entry<Serializable[], Serializable[]> row : eventData.getRows()) {
            Map<String, Object> before = toRowMap(row.getKey());
            Map<String, Object> after = toRowMap(row.getValue());
            Map<String, Object> primaryKey = extractPrimaryKey(after);
            CdcEventMessage message = CdcEventMessage.builder()
                    .database(tableMetadata.database())
                    .table(tableMetadata.table())
                    .eventType("UPDATE")
                    .primaryKey(primaryKey)
                    .before(extractChangedBefore(before, after))
                    .after(after)
                    .timestamp(System.currentTimeMillis())
                    .build();
            cdcEventPublisher.publish(message);
        }
    }

    private boolean isConfiguredTable(long tableId) {
        QualifiedTable qualifiedTable = tableMappings.get(tableId);
        if (qualifiedTable == null) {
            return false;
        }
        return Objects.equals(tableMetadata.database(), qualifiedTable.database())
                && Objects.equals(tableMetadata.table(), qualifiedTable.table());
    }

    private Map<String, Object> toRowMap(Serializable[] row) {
        Map<String, Object> rowMap = new LinkedHashMap<>();
        for (int index = 0; index < tableMetadata.columns().size() && index < row.length; index++) {
            rowMap.put(tableMetadata.columns().get(index), normalizeValue(row[index]));
        }
        return rowMap;
    }

    private Map<String, Object> extractPrimaryKey(Map<String, Object> row) {
        Map<String, Object> primaryKey = new LinkedHashMap<>();
        for (String primaryKeyColumn : tableMetadata.primaryKeys()) {
            primaryKey.put(primaryKeyColumn, row.get(primaryKeyColumn));
        }
        return primaryKey;
    }

    private Map<String, Object> extractChangedBefore(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changed = new LinkedHashMap<>();
        before.forEach((column, beforeValue) -> {
            Object afterValue = after.get(column);
            if (!Objects.equals(beforeValue, afterValue)) {
                changed.put(column, beforeValue);
            }
        });
        return changed;
    }

    private Object normalizeValue(Object value) {
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        return value;
    }

    private record QualifiedTable(String database, String table) {
    }

    private static final class NamedThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "mini-cdc-binlog");
            thread.setDaemon(true);
            return thread;
        }
    }
}
