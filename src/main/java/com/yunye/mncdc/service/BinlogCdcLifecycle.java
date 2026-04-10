package com.yunye.mncdc.service;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.github.shyiko.mysql.binlog.event.XidEventData;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.model.BinlogCheckpoint;
import com.yunye.mncdc.model.CdcTransactionEvent;
import com.yunye.mncdc.model.CdcTransactionRow;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
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

    private final CheckpointStore checkpointStore;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final ExecutorService executorService = Executors.newSingleThreadExecutor(new NamedThreadFactory());

    private final Map<Long, QualifiedTable> tableMappings = new ConcurrentHashMap<>();

    private final List<CdcTransactionRow> bufferedTransactionRows = new ArrayList<>();

    private volatile BinaryLogClient client;

    private volatile TableMetadata tableMetadata;

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            tableMetadata = tableMetadataService.getConfiguredTableMetadata();
            client = createClient(resolveStartupCheckpoint());
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
        bufferedTransactionRows.clear();
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

    private BinaryLogClient createClient(BinlogCheckpoint startupCheckpoint) {
        MiniCdcProperties.Mysql mysql = properties.getMysql();
        BinaryLogClient binaryLogClient = new BinaryLogClient(
                mysql.getHost(),
                mysql.getPort(),
                mysql.getUsername(),
                mysql.getPassword()
        );
        binaryLogClient.setServerId(mysql.getServerId());
        binaryLogClient.setKeepAlive(true);
        if (startupCheckpoint != null) {
            binaryLogClient.setBinlogFilename(startupCheckpoint.binlogFilename());
            binaryLogClient.setBinlogPosition(startupCheckpoint.binlogPosition());
            log.info(
                    "CDC startup checkpoint resolved to {}:{} for connector {}.",
                    startupCheckpoint.binlogFilename(),
                    startupCheckpoint.binlogPosition(),
                    startupCheckpoint.connectorName()
            );
        }
        binaryLogClient.registerEventListener(this::handleEvent);
        return binaryLogClient;
    }

    private void connectSafely() {
        try {
            client.connect();
        } catch (IOException exception) {
            boolean shuttingDown = !running.get();
            running.set(false);
            if (shuttingDown || (client != null && !client.isConnected())) {
                log.info("Binlog listener disconnected.");
                return;
            }
            log.error("Binlog listener stopped because MySQL connection failed.", exception);
        }
    }

    private void handleEvent(Event event) {
        EventHeaderV4 header = asHeaderV4(event);
        EventType eventType = header.getEventType();
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
            } else if (eventType == EventType.XID) {
                handleTransactionCommit((XidEventData) eventData, checkpointFor(header), header.getTimestamp());
            }
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Failed to process binlog event {}", eventType, exception);
            stopAfterFailure();
        }
    }

    private void handleInsert(WriteRowsEventData eventData) {
        if (!isConfiguredTable(eventData.getTableId())) {
            return;
        }
        bufferedTransactionRows.addAll(eventData.getRows().stream()
                .map(row -> {
                    Map<String, Object> after = toRowMap(row);
                    Map<String, Object> primaryKey = extractPrimaryKey(after);
                    return new CdcTransactionRow("INSERT", primaryKey, after);
                })
                .toList());
    }

    private void handleUpdate(UpdateRowsEventData eventData) {
        if (!isConfiguredTable(eventData.getTableId())) {
            return;
        }
        bufferedTransactionRows.addAll(eventData.getRows().stream()
                .map(row -> {
                    Map<String, Object> after = toRowMap(row.getValue());
                    Map<String, Object> primaryKey = extractPrimaryKey(after);
                    return new CdcTransactionRow("UPDATE", primaryKey, after);
                })
                .toList());
    }

    private boolean isConfiguredTable(long tableId) {
        QualifiedTable qualifiedTable = tableMappings.get(tableId);
        if (qualifiedTable == null) {
            return false;
        }
        return Objects.equals(tableMetadata.database(), qualifiedTable.database())
                && Objects.equals(tableMetadata.table(), qualifiedTable.table());
    }

    private void handleTransactionCommit(XidEventData xidEventData, BinlogCheckpoint checkpoint, long eventTimestamp)
            throws ExecutionException, InterruptedException {
        if (bufferedTransactionRows.isEmpty()) {
            return;
        }
        CdcTransactionEvent transactionEvent = new CdcTransactionEvent(
                buildTransactionId(xidEventData.getXid(), checkpoint), //用来做后续判断消费者是否重复消费
                properties.getCheckpoint().getConnectorName(),
                tableMetadata.database(),
                tableMetadata.table(),
                checkpoint.binlogFilename(),
                xidEventData.getXid(),
                checkpoint.binlogPosition(),
                eventTimestamp > 0 ? eventTimestamp : System.currentTimeMillis(),
                List.copyOf(bufferedTransactionRows)
        );
        cdcEventPublisher.publishTransaction(transactionEvent).get();
        if (properties.getCheckpoint().isEnabled()) {
            checkpointStore.save(checkpoint);
        }
        bufferedTransactionRows.clear();
    }

    private String buildTransactionId(long xid, BinlogCheckpoint checkpoint) {
        return properties.getCheckpoint().getConnectorName()
                + ":"
                + checkpoint.binlogFilename()
                + ":"
                + xid
                + ":"
                + checkpoint.binlogPosition();
    }

    private BinlogCheckpoint resolveStartupCheckpoint() {
        if (!properties.getCheckpoint().isEnabled()) {
            log.info("Checkpoint persistence is disabled. Binlog recovery will use BinaryLogClient defaults.");
            return null;
        }
        Optional<BinlogCheckpoint> storedCheckpoint = checkpointStore.load();
        if (storedCheckpoint.isPresent()) {
            BinlogCheckpoint checkpoint = storedCheckpoint.get();
            validateCheckpoint(checkpoint);
            return checkpoint;
        }
        if (properties.getCheckpoint().getStartupStrategy() == MiniCdcProperties.StartupStrategy.LATEST) {
            BinlogCheckpoint latestCheckpoint = checkpointStore.loadLatestServerCheckpoint();
            log.info(
                    "No stored checkpoint found for connector {}. Starting from latest MySQL position {}:{}.",
                    latestCheckpoint.connectorName(),
                    latestCheckpoint.binlogFilename(),
                    latestCheckpoint.binlogPosition()
            );
            return latestCheckpoint;
        }
        throw new IllegalStateException("Unsupported startup strategy: " + properties.getCheckpoint().getStartupStrategy());
    }

    private void validateCheckpoint(BinlogCheckpoint checkpoint) {
        if (!Objects.equals(tableMetadata.database(), checkpoint.databaseName())
                || !Objects.equals(tableMetadata.table(), checkpoint.tableName())) {
            throw new IllegalStateException(
                    "Stored checkpoint does not match configured table: "
                            + checkpoint.databaseName()
                            + "."
                            + checkpoint.tableName()
            );
        }
    }

    private BinlogCheckpoint checkpointFor(EventHeaderV4 header) {
        return new BinlogCheckpoint(
                properties.getCheckpoint().getConnectorName(),
                tableMetadata.database(),
                tableMetadata.table(),
                client.getBinlogFilename(),
                header.getNextPosition()
        );
    }

    private EventHeaderV4 asHeaderV4(Event event) {
        if (event.getHeader() instanceof EventHeaderV4 header) {
            return header;
        }
        throw new IllegalStateException("Unsupported binlog event header type: " + event.getHeader().getClass().getName());
    }

    private void stopAfterFailure() {
        running.set(false);
        tableMappings.clear();
        bufferedTransactionRows.clear();
        BinaryLogClient currentClient = client;
        if (currentClient == null) {
            return;
        }
        Thread shutdownThread = new NamedThreadFactory().newThread(() -> {
            try {
                currentClient.disconnect();
            } catch (IOException ioException) {
                log.warn("Failed to disconnect binlog client after processing error.", ioException);
            }
        });
        shutdownThread.start();
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
