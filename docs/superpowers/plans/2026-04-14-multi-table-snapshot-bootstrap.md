# Multi-Table Snapshot Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement snapshot-then-incremental startup for one connector listening to multiple tables by taking one connector-level cutover point, snapshotting configured tables serially, tracking progress per table, and writing the connector checkpoint only after all tables complete.

**Architecture:** Keep `cdc_offset` connector-scoped and reuse the existing snapshot page publish path. Change `full_sync_task` into a table-scoped operational progress table keyed by `(connector_name, database_name, table_name)`, then refactor `SnapshotBootstrapService` to iterate ordered `TableMetadata` entries serially while reusing the current per-table paging logic.

**Tech Stack:** Java 17, Spring Boot 3.5, MyBatis-Plus/MyBatis annotations, JUnit 5, Mockito, AssertJ, Maven Surefire

---

## File Map

- Modify: `src/main/sql/full-updat.sql`
  - Change `full_sync_task` primary key from connector-only to `(connector_name, database_name, table_name)`.
- Modify: `src/main/java/com/yunye/mncdc/entity/FullSyncTaskEntity.java`
  - Remove the connector-only identity assumption so the row can be addressed by the full table identity.
- Modify: `src/main/java/com/yunye/mncdc/mapper/FullSyncTaskMapper.java`
  - Replace connector-only update SQL with table-scoped SQL and add explicit table-scoped upsert SQL.
- Modify: `src/main/java/com/yunye/mncdc/checkpoint/FullSyncTaskStore.java`
  - Update progress, completion, and failure methods to require `(connector, database, table)`.
- Modify: `src/test/java/com/yunye/mncdc/checkpoint/FullSyncTaskStoreTest.java`
  - Lock in triad-scoped task persistence behavior.
- Modify: `src/main/java/com/yunye/mncdc/snapshot/SnapshotBootstrapService.java`
  - Change bootstrap input from one table to ordered multi-table input and run serial table snapshot loops.
- Modify: `src/test/java/com/yunye/mncdc/snapshot/SnapshotBootstrapServiceTest.java`
  - Cover serial table order, shared cutover, table-specific progress updates, and no final checkpoint on partial failure.
- Modify: `src/main/java/com/yunye/mncdc/cdc/BinlogCdcLifecycle.java`
  - Remove the single-table snapshot restriction and pass ordered metadata into the bootstrap service.
- Modify: `src/test/java/com/yunye/mncdc/cdc/BinlogCdcLifecycleTransactionTest.java`
  - Add startup-path coverage for multi-table snapshot bootstrap.
- Verify: `src/main/java/com/yunye/mncdc/metadata/TableMetadataService.java`
  - Reuse its configured-table ordering as the serial bootstrap order; no functional change expected.

### Task 1: Make `full_sync_task` Table-Scoped

**Files:**
- Modify: `src/main/sql/full-updat.sql`
- Modify: `src/main/java/com/yunye/mncdc/entity/FullSyncTaskEntity.java`
- Modify: `src/main/java/com/yunye/mncdc/mapper/FullSyncTaskMapper.java`
- Modify: `src/main/java/com/yunye/mncdc/checkpoint/FullSyncTaskStore.java`
- Modify: `src/test/java/com/yunye/mncdc/checkpoint/FullSyncTaskStoreTest.java`

- [ ] **Step 1: Write the failing store tests for triad-scoped updates**

```java
@Test
void markCompletedUpdatesOneSpecificTableRow() {
    when(fullSyncTaskMapper.markCompleted("mini-user-sync", "mini", "user")).thenReturn(1);

    store.markCompleted("mini-user-sync", "mini", "user");

    verify(fullSyncTaskMapper).markCompleted("mini-user-sync", "mini", "user");
}

@Test
void updateLastSentPkPersistsCursorForSpecificTableRow() {
    String cursor = "{\"id\":123}";
    when(fullSyncTaskMapper.updateLastSentPk("mini-user-sync", "mini", "order", cursor)).thenReturn(1);

    store.updateLastSentPk("mini-user-sync", "mini", "order", cursor);

    verify(fullSyncTaskMapper).updateLastSentPk("mini-user-sync", "mini", "order", cursor);
}

@Test
void markCompletedThrowsWhenSpecificTableRowIsMissing() {
    when(fullSyncTaskMapper.markCompleted("mini-user-sync", "mini", "user")).thenReturn(0);

    assertThatThrownBy(() -> store.markCompleted("mini-user-sync", "mini", "user"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Full sync task row was not found for table: mini-user-sync mini.user");
}
```

- [ ] **Step 2: Run the store test to confirm the current connector-only code fails**

Run: `mvn -Dtest=FullSyncTaskStoreTest test`
Expected: FAIL because `markCompleted/updateLastSentPk` currently accept only `connectorName`.

- [ ] **Step 3: Change the SQL schema and mapper to use `(connector, database, table)`**

```sql
CREATE TABLE full_sync_task (
    connector_name           VARCHAR(128) NOT NULL COMMENT 'CDC connector 标识；同一个 connector 下可对应多张监听表',
    database_name            VARCHAR(64)  NOT NULL COMMENT '全量同步对应的数据库名',
    table_name               VARCHAR(64)  NOT NULL COMMENT '全量同步对应的表名',
    status                   VARCHAR(32)  NOT NULL COMMENT '该表的全量任务状态：RUNNING / COMPLETED / FAILED',
    cutover_binlog_filename  VARCHAR(255) NOT NULL COMMENT '本次 connector 级全量开始前记录的 binlog 文件名；同一批次各表通常相同',
    cutover_binlog_position  BIGINT UNSIGNED NOT NULL COMMENT '本次 connector 级全量开始前记录的 binlog 位点；同一批次各表通常相同',
    last_sent_pk             JSON DEFAULT NULL COMMENT '该表最近一个已成功发送到 Kafka 的主键游标',
    started_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该表全量任务开始时间',
    finished_at              DATETIME DEFAULT NULL COMMENT '该表全量任务完成或失败时间',
    last_error               VARCHAR(1000) DEFAULT NULL COMMENT '该表最近一次失败原因',
    updated_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (connector_name, database_name, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全量同步任务状态表；一个 connector 下每张监听表一条记录';
```

```java
@Mapper
public interface FullSyncTaskMapper {

    @Insert("""
            INSERT INTO full_sync_task (
                connector_name,
                database_name,
                table_name,
                status,
                cutover_binlog_filename,
                cutover_binlog_position,
                last_sent_pk,
                started_at,
                finished_at,
                last_error
            ) VALUES (
                #{connectorName},
                #{databaseName},
                #{tableName},
                #{status},
                #{cutoverBinlogFilename},
                #{cutoverBinlogPosition},
                #{lastSentPk},
                #{startedAt},
                #{finishedAt},
                #{lastError}
            )
            ON DUPLICATE KEY UPDATE
                status = VALUES(status),
                cutover_binlog_filename = VALUES(cutover_binlog_filename),
                cutover_binlog_position = VALUES(cutover_binlog_position),
                last_sent_pk = VALUES(last_sent_pk),
                started_at = VALUES(started_at),
                finished_at = VALUES(finished_at),
                last_error = VALUES(last_error)
            """)
    int insertOrUpdate(FullSyncTaskEntity entity);

    @Update("""
            UPDATE full_sync_task
            SET last_sent_pk = #{lastSentPk}
            WHERE connector_name = #{connectorName}
              AND database_name = #{databaseName}
              AND table_name = #{tableName}
            """)
    int updateLastSentPk(
            @Param("connectorName") String connectorName,
            @Param("databaseName") String databaseName,
            @Param("tableName") String tableName,
            @Param("lastSentPk") String lastSentPk
    );

    @Update("""
            UPDATE full_sync_task
            SET status = 'COMPLETED',
                finished_at = CURRENT_TIMESTAMP,
                last_error = NULL
            WHERE connector_name = #{connectorName}
              AND database_name = #{databaseName}
              AND table_name = #{tableName}
            """)
    int markCompleted(
            @Param("connectorName") String connectorName,
            @Param("databaseName") String databaseName,
            @Param("tableName") String tableName
    );

    @Update("""
            UPDATE full_sync_task
            SET status = 'FAILED',
                finished_at = CURRENT_TIMESTAMP,
                last_error = #{lastError}
            WHERE connector_name = #{connectorName}
              AND database_name = #{databaseName}
              AND table_name = #{tableName}
            """)
    int markFailed(
            @Param("connectorName") String connectorName,
            @Param("databaseName") String databaseName,
            @Param("tableName") String tableName,
            @Param("lastError") String lastError
    );
}
```

- [ ] **Step 4: Update `FullSyncTaskStore` to require full table identity**

```java
public void markCompleted(String connectorName, String databaseName, String tableName) {
    try {
        assertTaskUpdated(
                connectorName,
                databaseName,
                tableName,
                fullSyncTaskMapper.markCompleted(connectorName, databaseName, tableName)
        );
    } catch (PersistenceException | DataAccessException exception) {
        throw new IllegalStateException("Failed to persist full sync task.", exception);
    }
}

public void updateLastSentPk(String connectorName, String databaseName, String tableName, String lastSentPk) {
    try {
        assertTaskUpdated(
                connectorName,
                databaseName,
                tableName,
                fullSyncTaskMapper.updateLastSentPk(connectorName, databaseName, tableName, lastSentPk)
        );
    } catch (PersistenceException | DataAccessException exception) {
        throw new IllegalStateException("Failed to persist full sync task.", exception);
    }
}

public void markFailed(String connectorName, String databaseName, String tableName, String lastError) {
    try {
        assertTaskUpdated(
                connectorName,
                databaseName,
                tableName,
                fullSyncTaskMapper.markFailed(connectorName, databaseName, tableName, lastError)
        );
    } catch (PersistenceException | DataAccessException exception) {
        throw new IllegalStateException("Failed to persist full sync task.", exception);
    }
}

private void assertTaskUpdated(String connectorName, String databaseName, String tableName, int updatedRows) {
    if (updatedRows == 0) {
        throw new IllegalStateException(
                "Full sync task row was not found for table: " + connectorName + " " + databaseName + "." + tableName
        );
    }
}
```

- [ ] **Step 5: Run the store test to verify triad-scoped persistence passes**

Run: `mvn -Dtest=FullSyncTaskStoreTest test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/sql/full-updat.sql src/main/java/com/yunye/mncdc/entity/FullSyncTaskEntity.java src/main/java/com/yunye/mncdc/mapper/FullSyncTaskMapper.java src/main/java/com/yunye/mncdc/checkpoint/FullSyncTaskStore.java src/test/java/com/yunye/mncdc/checkpoint/FullSyncTaskStoreTest.java
git commit -m "refactor: make full sync tasks table scoped"
```

### Task 2: Refactor `SnapshotBootstrapService` For Serial Multi-Table Bootstrap

**Files:**
- Modify: `src/main/java/com/yunye/mncdc/snapshot/SnapshotBootstrapService.java`
- Modify: `src/test/java/com/yunye/mncdc/snapshot/SnapshotBootstrapServiceTest.java`

- [ ] **Step 1: Write the failing multi-table snapshot test**

```java
@Test
void bootstrapProcessesConfiguredTablesSeriallyAndWritesOneCheckpointAfterAllTablesComplete() {
    TableMetadata userTable = new TableMetadata("mini", "user", List.of("id", "username"), List.of("id"));
    TableMetadata orderTable = new TableMetadata("mini", "order", List.of("id", "user_id"), List.of("id"));

    when(checkpointStore.loadLatestServerCheckpoint()).thenReturn(cutoverCheckpoint);
    when(snapshotReader.readConsistentSnapshot(any())).thenAnswer(invocation -> {
        SnapshotReader.SnapshotWork<?> work = invocation.getArgument(0);
        return work.execute(connection);
    });
    when(cdcEventPublisher.publishSnapshotPage(any())).thenReturn(CompletableFuture.completedFuture(null));

    SnapshotBootstrapService service = new TestSnapshotBootstrapService(
            checkpointStore,
            fullSyncTaskStore,
            snapshotReader,
            cdcEventPublisher,
            objectMapper,
            properties,
            Map.of(
                    "mini.user", List.of(
                            new SnapshotPage(List.of(Map.of("id", 1L, "username", "alice")), Map.of("id", 1L), false)
                    ),
                    "mini.order", List.of(
                            new SnapshotPage(List.of(Map.of("id", 10L, "user_id", 1L)), Map.of("id", 10L), false)
                    )
            )
    );

    BinlogCheckpoint result = service.bootstrap(List.of(userTable, orderTable));

    assertThat(result).isEqualTo(cutoverCheckpoint);
    verify(fullSyncTaskStore).start(new FullSyncTask("mini-user-sync", "mini", "user", FullSyncTaskStatus.RUNNING, "mysql-bin.000321", 678L, null, null, null));
    verify(fullSyncTaskStore).start(new FullSyncTask("mini-user-sync", "mini", "order", FullSyncTaskStatus.RUNNING, "mysql-bin.000321", 678L, null, null, null));
    verify(fullSyncTaskStore).updateLastSentPk("mini-user-sync", "mini", "user", "{\"id\":1}");
    verify(fullSyncTaskStore).updateLastSentPk("mini-user-sync", "mini", "order", "{\"id\":10}");
    verify(fullSyncTaskStore).markCompleted("mini-user-sync", "mini", "user");
    verify(fullSyncTaskStore).markCompleted("mini-user-sync", "mini", "order");
    verify(checkpointStore).save(cutoverCheckpoint);
    assertThat(((TestSnapshotBootstrapService) service).visitedTables()).containsExactly("mini.user", "mini.order");
}
```

- [ ] **Step 2: Add the failing partial-failure test**

```java
@Test
void bootstrapMarksCurrentTableFailedAndSkipsFormalCheckpointWhenSecondTablePublishFails() {
    TableMetadata userTable = new TableMetadata("mini", "user", List.of("id", "username"), List.of("id"));
    TableMetadata orderTable = new TableMetadata("mini", "order", List.of("id", "user_id"), List.of("id"));

    when(checkpointStore.loadLatestServerCheckpoint()).thenReturn(cutoverCheckpoint);
    when(snapshotReader.readConsistentSnapshot(any())).thenAnswer(invocation -> {
        SnapshotReader.SnapshotWork<?> work = invocation.getArgument(0);
        return work.execute(connection);
    });
    when(cdcEventPublisher.publishSnapshotPage(any()))
            .thenReturn(CompletableFuture.completedFuture(null))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("kafka down")));

    SnapshotBootstrapService service = new TestSnapshotBootstrapService(
            checkpointStore,
            fullSyncTaskStore,
            snapshotReader,
            cdcEventPublisher,
            objectMapper,
            properties,
            Map.of(
                    "mini.user", List.of(new SnapshotPage(List.of(Map.of("id", 1L, "username", "alice")), Map.of("id", 1L), false)),
                    "mini.order", List.of(new SnapshotPage(List.of(Map.of("id", 10L, "user_id", 1L)), Map.of("id", 10L), false))
            )
    );

    assertThatThrownBy(() -> service.bootstrap(List.of(userTable, orderTable)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Failed to publish snapshot page.")
            .hasRootCauseMessage("kafka down");

    verify(fullSyncTaskStore).markCompleted("mini-user-sync", "mini", "user");
    verify(fullSyncTaskStore).markFailed("mini-user-sync", "mini", "order", "Failed to publish snapshot page.: kafka down");
    verify(checkpointStore, never()).save(any());
}
```

- [ ] **Step 3: Run the snapshot bootstrap test to verify the current single-table implementation fails**

Run: `mvn -Dtest=SnapshotBootstrapServiceTest test`
Expected: FAIL because `bootstrap` currently accepts only one `TableMetadata` and task updates are connector-only.

- [ ] **Step 4: Change `SnapshotBootstrapService` to accept ordered multi-table input**

```java
public BinlogCheckpoint bootstrap(List<TableMetadata> orderedTables) {
    if (orderedTables == null || orderedTables.isEmpty()) {
        throw new IllegalStateException("At least one configured table is required for snapshot bootstrap.");
    }

    BinlogCheckpoint cutoverCheckpoint = checkpointStore.loadLatestServerCheckpoint();
    initializeTasks(orderedTables, cutoverCheckpoint);

    try {
        for (TableMetadata tableMetadata : orderedTables) {
            snapshotReader.readConsistentSnapshot(connection -> {
                publishSnapshotPages(connection, tableMetadata, cutoverCheckpoint);
                return null;
            });
            fullSyncTaskStore.markCompleted(
                    cutoverCheckpoint.connectorName(),
                    tableMetadata.database(),
                    tableMetadata.table()
            );
        }
        checkpointStore.save(cutoverCheckpoint);
        return cutoverCheckpoint;
    } catch (RuntimeException exception) {
        throw exception;
    }
}

private void initializeTasks(List<TableMetadata> orderedTables, BinlogCheckpoint cutoverCheckpoint) {
    for (TableMetadata tableMetadata : orderedTables) {
        fullSyncTaskStore.start(new FullSyncTask(
                cutoverCheckpoint.connectorName(),
                tableMetadata.database(),
                tableMetadata.table(),
                FullSyncTaskStatus.RUNNING,
                cutoverCheckpoint.binlogFilename(),
                cutoverCheckpoint.binlogPosition(),
                null,
                null,
                null
        ));
    }
}
```

- [ ] **Step 5: Route page progress updates and failures to the current table row**

```java
protected void publishSnapshotPages(
        Connection connection,
        TableMetadata tableMetadata,
        BinlogCheckpoint cutoverCheckpoint
) throws SQLException {
    Map<String, Object> lastPrimaryKey = null;
    int pageIndex = 0;
    while (true) {
        SnapshotPage page = readPage(connection, tableMetadata, lastPrimaryKey, properties.getSnapshot().getPageSize());
        if (page.rows().isEmpty()) {
            return;
        }

        CdcTransactionEvent snapshotEvent = buildSnapshotEvent(tableMetadata, cutoverCheckpoint, pageIndex, page.rows());
        publishSnapshotPage(snapshotEvent);
        fullSyncTaskStore.updateLastSentPk(
                cutoverCheckpoint.connectorName(),
                tableMetadata.database(),
                tableMetadata.table(),
                serializePrimaryKey(orderedPrimaryKey(tableMetadata, page.lastPrimaryKey()))
        );

        if (!page.hasMore()) {
            return;
        }
        lastPrimaryKey = page.lastPrimaryKey();
        pageIndex++;
    }
}

private void markTableFailed(TableMetadata tableMetadata, BinlogCheckpoint cutoverCheckpoint, RuntimeException exception) {
    fullSyncTaskStore.markFailed(
            cutoverCheckpoint.connectorName(),
            tableMetadata.database(),
            tableMetadata.table(),
            failureMessage(exception)
    );
}
```

- [ ] **Step 6: Run the snapshot bootstrap test to verify serial multi-table behavior passes**

Run: `mvn -Dtest=SnapshotBootstrapServiceTest test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yunye/mncdc/snapshot/SnapshotBootstrapService.java src/test/java/com/yunye/mncdc/snapshot/SnapshotBootstrapServiceTest.java
git commit -m "feat: support serial multi-table snapshot bootstrap"
```

### Task 3: Wire Multi-Table Snapshot Startup Into `BinlogCdcLifecycle`

**Files:**
- Modify: `src/main/java/com/yunye/mncdc/cdc/BinlogCdcLifecycle.java`
- Modify: `src/test/java/com/yunye/mncdc/cdc/BinlogCdcLifecycleTransactionTest.java`

- [ ] **Step 1: Write the failing lifecycle startup test**

```java
@Test
void snapshotStartupBootstrapsAllConfiguredTablesInsteadOfRejectingMultiTableConfig() {
    when(checkpointStore.load()).thenReturn(Optional.empty());
    when(checkpointProperties.getStartupStrategy()).thenReturn(MiniCdcProperties.StartupStrategy.SNAPSHOT_THEN_INCREMENTAL);
    when(tableMetadataService.getConfiguredTableMetadata()).thenReturn(metadataByTable(USER_TABLE, USER_METADATA, ORDER_TABLE, ORDER_METADATA));
    when(snapshotBootstrapService.bootstrap(List.of(USER_METADATA, ORDER_METADATA)))
            .thenReturn(new BinlogCheckpoint("mini-user-sync", "mysql-bin.000009", 256L));

    lifecycle = new TestSnapshotLifecycle(
            properties,
            tableMetadataService,
            publisher,
            checkpointStore,
            snapshotBootstrapService,
            binaryLogClient
    );

    lifecycle.start();

    verify(snapshotBootstrapService).bootstrap(List.of(USER_METADATA, ORDER_METADATA));
    verify(binaryLogClient).setBinlogFilename("mysql-bin.000009");
    verify(binaryLogClient).setBinlogPosition(256L);
}
```

- [ ] **Step 2: Run the lifecycle test to verify the current single-table guard fails**

Run: `mvn -Dtest=BinlogCdcLifecycleTransactionTest test`
Expected: FAIL because `resolveSnapshotBootstrapTable()` currently throws when more than one configured table exists.

- [ ] **Step 3: Replace the single-table bootstrap path with ordered multi-table bootstrap**

```java
private BinlogCheckpoint resolveStartupCheckpoint() {
    MiniCdcProperties.Checkpoint checkpointProperties = properties.getCheckpoint();
    MiniCdcProperties.StartupStrategy startupStrategy = checkpointProperties.getStartupStrategy();
    if (!checkpointProperties.isEnabled()) {
        if (startupStrategy == MiniCdcProperties.StartupStrategy.SNAPSHOT_THEN_INCREMENTAL) {
            throw new IllegalStateException("Snapshot-then-incremental startup requires checkpoint persistence to be enabled.");
        }
        return null;
    }
    Optional<BinlogCheckpoint> storedCheckpoint = checkpointStore.load();
    if (storedCheckpoint.isPresent()) {
        return storedCheckpoint.get();
    }
    if (startupStrategy == MiniCdcProperties.StartupStrategy.LATEST) {
        return checkpointStore.loadLatestServerCheckpoint();
    }
    if (startupStrategy == MiniCdcProperties.StartupStrategy.SNAPSHOT_THEN_INCREMENTAL) {
        if (snapshotBootstrapService == null) {
            throw new IllegalStateException("Snapshot bootstrap service is required for SNAPSHOT_THEN_INCREMENTAL startup.");
        }
        return snapshotBootstrapService.bootstrap(resolveOrderedSnapshotBootstrapTables());
    }
    throw new IllegalStateException("Unsupported startup strategy: " + startupStrategy);
}

private List<TableMetadata> resolveOrderedSnapshotBootstrapTables() {
    Map<QualifiedTable, TableMetadata> metadataByTable = tableMetadataByTable;
    if (metadataByTable == null || metadataByTable.isEmpty()) {
        throw new IllegalStateException("CDC lifecycle metadata has not been initialized.");
    }
    return List.copyOf(metadataByTable.values());
}
```

- [ ] **Step 4: Run the lifecycle test to verify multi-table snapshot startup passes**

Run: `mvn -Dtest=BinlogCdcLifecycleTransactionTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yunye/mncdc/cdc/BinlogCdcLifecycle.java src/test/java/com/yunye/mncdc/cdc/BinlogCdcLifecycleTransactionTest.java
git commit -m "feat: wire serial multi-table snapshot startup"
```

### Task 4: Run Regression Coverage For Multi-Table Snapshot Bootstrap

**Files:**
- Verify: `src/test/java/com/yunye/mncdc/checkpoint/FullSyncTaskStoreTest.java`
- Verify: `src/test/java/com/yunye/mncdc/snapshot/SnapshotBootstrapServiceTest.java`
- Verify: `src/test/java/com/yunye/mncdc/cdc/BinlogCdcLifecycleTransactionTest.java`
- Verify: `src/test/java/com/yunye/mncdc/checkpoint/CheckpointStoreTest.java`

- [ ] **Step 1: Run focused multi-table snapshot tests**

Run: `mvn -Dtest=FullSyncTaskStoreTest,SnapshotBootstrapServiceTest,BinlogCdcLifecycleTransactionTest test`
Expected: PASS

- [ ] **Step 2: Run the checkpoint regression together with snapshot startup**

Run: `mvn -Dtest=CheckpointStoreTest,FullSyncTaskStoreTest,SnapshotBootstrapServiceTest,BinlogCdcLifecycleTransactionTest test`
Expected: PASS

- [ ] **Step 3: Spot-check the diff scope**

Run: `git diff -- src/main/java/com/yunye/mncdc/checkpoint src/main/java/com/yunye/mncdc/snapshot src/main/java/com/yunye/mncdc/cdc src/main/java/com/yunye/mncdc/entity src/main/java/com/yunye/mncdc/mapper src/main/sql/full-updat.sql src/test/java/com/yunye/mncdc/checkpoint src/test/java/com/yunye/mncdc/snapshot src/test/java/com/yunye/mncdc/cdc`
Expected: Only table-scoped `full_sync_task` changes, serial multi-table bootstrap, and snapshot startup integration appear.

- [ ] **Step 4: Commit the verified regression state**

```bash
git add src/main/java/com/yunye/mncdc/checkpoint src/main/java/com/yunye/mncdc/snapshot src/main/java/com/yunye/mncdc/cdc src/main/java/com/yunye/mncdc/entity src/main/java/com/yunye/mncdc/mapper src/main/sql/full-updat.sql src/test/java/com/yunye/mncdc/checkpoint src/test/java/com/yunye/mncdc/snapshot src/test/java/com/yunye/mncdc/cdc
git commit -m "test: verify multi-table snapshot bootstrap"
```

## Self-Review

- Spec coverage: this plan covers the approved design's four main requirements: shared connector cutover, serial configured-table execution, per-table `full_sync_task` progress, and connector-level `cdc_offset` write only after all tables complete.
- Placeholder scan: no `TODO`, `TBD`, or “similar to above” placeholders remain; every task includes concrete files, tests, commands, and implementation snippets.
- Type consistency: the plan uses one table identity shape throughout: `(connectorName, databaseName, tableName)` for task updates, `List<TableMetadata>` for ordered bootstrap input, and connector-scoped `BinlogCheckpoint` for the final formal recovery point.
