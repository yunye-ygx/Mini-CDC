package com.yunye.mncdc.cdc;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.QueryEventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.github.shyiko.mysql.binlog.event.XidEventData;
import com.yunye.mncdc.checkpoint.CheckpointStore;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.ddl.SchemaChangeClassifier;
import com.yunye.mncdc.metadata.TableMetadataService;
import com.yunye.mncdc.model.BinlogCheckpoint;
import com.yunye.mncdc.model.CdcSchemaChangeEvent;
import com.yunye.mncdc.model.CdcTransactionEvent;
import com.yunye.mncdc.model.CdcTransactionRow;
import com.yunye.mncdc.model.QualifiedTable;
import com.yunye.mncdc.model.TableMetadata;
import com.yunye.mncdc.snapshot.SnapshotBootstrapService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Locale;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "mini-cdc", name = "enabled", havingValue = "true")
public class BinlogCdcLifecycle implements SmartLifecycle {

    private static final Set<String> INTERNAL_PROGRESS_TABLES = Set.of("cdc_offset", "full_sync_task");
    private static final Pattern ALTER_TABLE_PATTERN = Pattern.compile(
            "(?i)^\\s*ALTER\\s+TABLE\\s+((?:`[^`]+`|[\\w]+)(?:\\.(?:`[^`]+`|[\\w]+))?)"
    );

    private final MiniCdcProperties properties;

    private final TableMetadataService tableMetadataService;

    private final CdcEventPublisher cdcEventPublisher;

    private final CheckpointStore checkpointStore;

    private final SnapshotBootstrapService snapshotBootstrapService;

    private final SchemaChangeClassifier schemaChangeClassifier;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final ExecutorService executorService = Executors.newSingleThreadExecutor(new NamedThreadFactory());

    private final Map<Long, QualifiedTable> tableMappings = new ConcurrentHashMap<>();

    private final List<CdcTransactionRow> bufferedTransactionRows = new ArrayList<>();

    private final Set<QualifiedTable> ignoredTablesInTransaction = new LinkedHashSet<>();

    private volatile BinaryLogClient client;

    private volatile Map<QualifiedTable, TableMetadata> tableMetadataByTable;

    private volatile Set<QualifiedTable> configuredTables;

    @Autowired
    public BinlogCdcLifecycle(
            MiniCdcProperties properties,
            TableMetadataService tableMetadataService,
            CdcEventPublisher cdcEventPublisher,
            CheckpointStore checkpointStore,
            SnapshotBootstrapService snapshotBootstrapService,
            SchemaChangeClassifier schemaChangeClassifier
    ) {
        this.properties = properties;
        this.tableMetadataService = tableMetadataService;
        this.cdcEventPublisher = cdcEventPublisher;
        this.checkpointStore = checkpointStore;
        this.snapshotBootstrapService = snapshotBootstrapService;
        this.schemaChangeClassifier = schemaChangeClassifier;
    }

    public BinlogCdcLifecycle(
            MiniCdcProperties properties,
            TableMetadataService tableMetadataService,
            CdcEventPublisher cdcEventPublisher,
            CheckpointStore checkpointStore
    ) {
        this(
                properties,
                tableMetadataService,
                cdcEventPublisher,
                checkpointStore,
                null,
                new SchemaChangeClassifier()
        );
    }

    public BinlogCdcLifecycle(
            MiniCdcProperties properties,
            TableMetadataService tableMetadataService,
            CdcEventPublisher cdcEventPublisher,
            CheckpointStore checkpointStore,
            SnapshotBootstrapService snapshotBootstrapService
    ) {
        this(
                properties,
                tableMetadataService,
                cdcEventPublisher,
                checkpointStore,
                snapshotBootstrapService,
                new SchemaChangeClassifier()
        );
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            tableMetadataByTable = tableMetadataService.getConfiguredTableMetadata();
            configuredTables = Set.copyOf(tableMetadataByTable.keySet());
            client = createClient(resolveStartupCheckpoint());
            executorService.submit(this::connectSafely);
            log.info("Mini CDC listener starting for tables {}", configuredTables);
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
        ignoredTablesInTransaction.clear();
        tableMetadataByTable = null;
        configuredTables = null;
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

    protected BinaryLogClient createClient(BinlogCheckpoint startupCheckpoint) {
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
            if (eventType == EventType.QUERY && eventData instanceof QueryEventData queryEventData) {
                handleQueryEvent(queryEventData, checkpointFor(header), header.getTimestamp());
            } else if (eventType == EventType.EXT_WRITE_ROWS || eventType == EventType.WRITE_ROWS) {
                handleInsert((WriteRowsEventData) eventData);
            } else if (eventType == EventType.EXT_UPDATE_ROWS || eventType == EventType.UPDATE_ROWS) {
                handleUpdate((UpdateRowsEventData) eventData);
            } else if (eventType == EventType.EXT_DELETE_ROWS || eventType == EventType.DELETE_ROWS) {
                handleDelete((DeleteRowsEventData) eventData);
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
        QualifiedTable table = mappedTable(eventData.getTableId());
        if (table == null) {
            return;
        }
        if (!isConfiguredTable(table)) {
            ignoredTablesInTransaction.add(table);
            return;
        }
        TableMetadata metadata = requireMetadata(table);
        for (Serializable[] row : eventData.getRows()) {
            Map<String, Object> after = toRowMap(metadata, row);
            appendBufferedRow(table, "INSERT", extractPrimaryKey(metadata, after), null, after);
        }
    }

    private void handleUpdate(UpdateRowsEventData eventData) {
        QualifiedTable table = mappedTable(eventData.getTableId());
        if (table == null) {
            return;
        }
        if (!isConfiguredTable(table)) {
            ignoredTablesInTransaction.add(table);
            return;
        }
        TableMetadata metadata = requireMetadata(table);
        for (Map.Entry<Serializable[], Serializable[]> row : eventData.getRows()) {
            Map<String, Object> before = toRowMap(metadata, row.getKey());
            Map<String, Object> after = toRowMap(metadata, row.getValue());
            assertPrimaryKeyUnchanged(metadata, before, after);
            appendBufferedRow(table, "UPDATE", extractPrimaryKey(metadata, after), before, after);
        }
    }

    private void handleDelete(DeleteRowsEventData eventData) {
        QualifiedTable table = mappedTable(eventData.getTableId());
        if (table == null) {
            return;
        }
        if (!isConfiguredTable(table)) {
            ignoredTablesInTransaction.add(table);
            return;
        }
        TableMetadata metadata = requireMetadata(table);
        for (Serializable[] row : eventData.getRows()) {
            Map<String, Object> before = toRowMap(metadata, row);
            appendBufferedRow(table, "DELETE", extractPrimaryKey(metadata, before), before, null);
        }
    }

    private void appendBufferedRow(
            QualifiedTable table,
            String eventType,
            Map<String, Object> primaryKey,
            Map<String, Object> before,
            Map<String, Object> after
    ) {
        bufferedTransactionRows.add(new CdcTransactionRow(
                table.database(),
                table.table(),
                bufferedTransactionRows.size(),
                eventType,
                primaryKey,
                before,
                after
        ));
    }

    private QualifiedTable mappedTable(long tableId) {
        return tableMappings.get(tableId);
    }

    private boolean isConfiguredTable(QualifiedTable qualifiedTable) {
        Set<QualifiedTable> listenedTables = configuredTables;
        return listenedTables != null && listenedTables.contains(qualifiedTable);
    }

    private TableMetadata requireMetadata(QualifiedTable table) {
        Map<QualifiedTable, TableMetadata> metadataByTable = tableMetadataByTable;
        if (metadataByTable == null) {
            throw new IllegalStateException("CDC lifecycle metadata has not been initialized.");
        }
        TableMetadata metadata = metadataByTable.get(table);
        if (metadata == null) {
            throw new IllegalStateException("No metadata loaded for configured table " + table.database() + "." + table.table());
        }
        return metadata;
    }

    private void handleTransactionCommit(XidEventData xidEventData, BinlogCheckpoint checkpoint, long eventTimestamp)
            throws ExecutionException, InterruptedException {
        if (bufferedTransactionRows.isEmpty()) {
            if (properties.getCheckpoint().isEnabled() && shouldPersistIgnoredTransactionCheckpoint()) {
                checkpointStore.save(checkpoint);
            }
            clearTransactionState();
            return;
        }
        CdcTransactionEvent transactionEvent = new CdcTransactionEvent(
                buildTransactionId(xidEventData.getXid(), checkpoint), //用来做后续判断消费者是否重复消费
                properties.getCheckpoint().getConnectorName(),
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
        clearTransactionState();
    }

    private void handleQueryEvent(QueryEventData queryEventData, BinlogCheckpoint checkpoint, long eventTimestamp)
            throws ExecutionException, InterruptedException {
        QualifiedTable table = resolveConfiguredTable(queryEventData.getDatabase(), queryEventData.getSql());
        SchemaChangeClassifier.SchemaChange change = schemaChangeClassifier.classify(table, queryEventData.getSql());
        if (change == null) {
            return;
        }
        cdcEventPublisher.publishSchemaChange(toSchemaChangeEvent(change, checkpoint, eventTimestamp)).get();
        tableMetadataByTable = tableMetadataService.refreshConfiguredTableMetadata();
        configuredTables = Set.copyOf(tableMetadataByTable.keySet());
        if (properties.getCheckpoint().isEnabled()) {
            checkpointStore.save(checkpoint);
        }
    }

    private boolean shouldPersistIgnoredTransactionCheckpoint() {
        if (ignoredTablesInTransaction.isEmpty()) {
            return true;
        }
        return ignoredTablesInTransaction.stream().anyMatch(table -> !isInternalProgressTable(table));
    }

    private boolean isInternalProgressTable(QualifiedTable table) {
        return INTERNAL_PROGRESS_TABLES.contains(table.table());
    }

    private void clearTransactionState() {
        bufferedTransactionRows.clear();
        ignoredTablesInTransaction.clear();
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

    private QualifiedTable resolveConfiguredTable(String database, String sql) {
        if (database == null || sql == null) {
            return null;
        }
        Matcher matcher = ALTER_TABLE_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return null;
        }
        String tableIdentifier = matcher.group(1);
        String resolvedDatabase = database;
        String resolvedTable = tableIdentifier;
        int separatorIndex = tableIdentifier.indexOf('.');
        if (separatorIndex >= 0) {
            resolvedDatabase = unquoteIdentifier(tableIdentifier.substring(0, separatorIndex));
            resolvedTable = tableIdentifier.substring(separatorIndex + 1);
        }
        return findConfiguredTableMatch(
                unquoteIdentifier(resolvedDatabase),
                unquoteIdentifier(resolvedTable)
        );
    }

    private String unquoteIdentifier(String identifier) {
        String trimmed = identifier == null ? null : identifier.trim();
        if (trimmed == null) {
            return null;
        }
        if (trimmed.startsWith("`") && trimmed.endsWith("`") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private QualifiedTable findConfiguredTableMatch(String database, String table) {
        if (database == null || table == null) {
            return null;
        }
        String normalizedDatabase = normalizeIdentifier(database);
        String normalizedTable = normalizeIdentifier(table);
        Set<QualifiedTable> listenedTables = configuredTables;
        if (listenedTables == null) {
            return null;
        }
        for (QualifiedTable configuredTable : listenedTables) {
            if (normalizeIdentifier(configuredTable.database()).equals(normalizedDatabase)
                    && normalizeIdentifier(configuredTable.table()).equals(normalizedTable)) {
                return configuredTable;
            }
        }
        return null;
    }

    private String normalizeIdentifier(String identifier) {
        return identifier == null ? null : identifier.trim().toLowerCase(Locale.ROOT);
    }

    private CdcSchemaChangeEvent toSchemaChangeEvent(
            SchemaChangeClassifier.SchemaChange change,
            BinlogCheckpoint checkpoint,
            long eventTimestamp
    ) {
        QualifiedTable table = change.table();
        return new CdcSchemaChangeEvent(
                "ddl:" + checkpoint.binlogFilename() + ":" + checkpoint.binlogPosition(),
                properties.getCheckpoint().getConnectorName(),
                table.database(),
                table.table(),
                change.ddlType(),
                change.rawSql(),
                checkpoint.binlogFilename(),
                checkpoint.binlogPosition(),
                eventTimestamp > 0 ? eventTimestamp : System.currentTimeMillis()
        );
    }

    private BinlogCheckpoint resolveStartupCheckpoint() {
        MiniCdcProperties.Checkpoint checkpointProperties = properties.getCheckpoint();
        MiniCdcProperties.StartupStrategy startupStrategy = checkpointProperties.getStartupStrategy();
        if (!checkpointProperties.isEnabled()) {
            if (startupStrategy == MiniCdcProperties.StartupStrategy.SNAPSHOT_THEN_INCREMENTAL) {
                throw new IllegalStateException(
                        "Snapshot-then-incremental startup requires checkpoint persistence to be enabled."
                );
            }
            log.info("Checkpoint persistence is disabled. Binlog recovery will use BinaryLogClient defaults.");
            return null;
        }
        Optional<BinlogCheckpoint> storedCheckpoint = checkpointStore.load();
        if (storedCheckpoint.isPresent()) {
            return storedCheckpoint.get();
        }
        if (startupStrategy == MiniCdcProperties.StartupStrategy.LATEST) {
            BinlogCheckpoint latestCheckpoint = checkpointStore.loadLatestServerCheckpoint();
            log.info(
                    "No stored checkpoint found for connector {}. Starting from latest MySQL position {}:{}.",
                    latestCheckpoint.connectorName(),
                    latestCheckpoint.binlogFilename(),
                    latestCheckpoint.binlogPosition()
            );
            return latestCheckpoint;
        }
        if (startupStrategy == MiniCdcProperties.StartupStrategy.SNAPSHOT_THEN_INCREMENTAL) {
            if (snapshotBootstrapService == null) {
                throw new IllegalStateException("Snapshot bootstrap service is required for SNAPSHOT_THEN_INCREMENTAL startup.");
            }
            BinlogCheckpoint snapshotCheckpoint = snapshotBootstrapService.bootstrap(resolveSnapshotBootstrapTables());
            log.info(
                    "No stored checkpoint found for connector {}. Snapshot bootstrap completed at {}:{}.",
                    snapshotCheckpoint.connectorName(),
                    snapshotCheckpoint.binlogFilename(),
                    snapshotCheckpoint.binlogPosition()
            );
            return snapshotCheckpoint;
        }
        throw new IllegalStateException("Unsupported startup strategy: " + startupStrategy);
    }

    private List<TableMetadata> resolveSnapshotBootstrapTables() {
        Map<QualifiedTable, TableMetadata> metadataByTable = tableMetadataByTable;
        if (metadataByTable == null || metadataByTable.isEmpty()) {
            throw new IllegalStateException("CDC lifecycle metadata has not been initialized.");
        }
        return List.copyOf(metadataByTable.values());
    }

    private BinlogCheckpoint checkpointFor(EventHeaderV4 header) {
        return new BinlogCheckpoint(
                properties.getCheckpoint().getConnectorName(),
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
        ignoredTablesInTransaction.clear();
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

    private Map<String, Object> toRowMap(TableMetadata metadata, Serializable[] row) {
        Map<String, Object> rowMap = new LinkedHashMap<>();
        for (int index = 0; index < metadata.columns().size() && index < row.length; index++) {
            rowMap.put(metadata.columns().get(index), normalizeValue(row[index]));
        }
        return rowMap;
    }

    private Map<String, Object> extractPrimaryKey(TableMetadata metadata, Map<String, Object> row) {
        Map<String, Object> primaryKey = new LinkedHashMap<>();
        for (String primaryKeyColumn : metadata.primaryKeys()) {
            primaryKey.put(primaryKeyColumn, row.get(primaryKeyColumn));
        }
        return primaryKey;
    }

    private void assertPrimaryKeyUnchanged(TableMetadata metadata, Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> beforePrimaryKey = extractPrimaryKey(metadata, before);
        Map<String, Object> afterPrimaryKey = extractPrimaryKey(metadata, after);
        if (!beforePrimaryKey.equals(afterPrimaryKey)) {
            throw new IllegalStateException("Primary key mutation is not supported.");
        }
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

    private static final class NamedThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "mini-cdc-binlog");
            thread.setDaemon(true);
            return thread;
        }
    }
}
