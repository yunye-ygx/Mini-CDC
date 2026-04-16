# Observability Ops Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `Actuator + Ops API +` a same-repo read-only dashboard so the CDC project exposes standard service health, CDC-specific operational state, and recent runtime evidence for interview demos.

**Architecture:** Keep observability inside the existing Spring Boot service. Standard endpoints come from Actuator, CDC-domain state comes from store-backed read models and a custom health indicator, and runtime event visibility comes from a bounded in-memory buffer plus Micrometer metrics updated at existing publish/consume/rebuild checkpoints.

**Tech Stack:** Spring Boot Actuator, Micrometer Prometheus registry, Spring MVC, MyBatis, JUnit 5, Mockito, MockMvc, plain HTML/CSS/JavaScript

---

### Task 1: Enable Actuator And Management Exposure

**Files:**
- Modify: `C:\javapractice\mn-CDC\pom.xml`
- Modify: `C:\javapractice\mn-CDC\src\main\resources\application.yml`
- Create: `C:\javapractice\mn-CDC\src\test\java\com\yunye\mncdc\ops\ActuatorSurfaceIntegrationTest.java`

- [ ] **Step 1: Write the failing actuator integration test**

Create `ActuatorSurfaceIntegrationTest`:

```java
package com.yunye.mncdc.ops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mini-cdc.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:actuator-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class ActuatorSurfaceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KafkaAdmin kafkaAdmin;

    @Test
    void exposesHealthAndMetricsEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());

        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
    }
}
```

- [ ] **Step 2: Run the actuator integration test to verify it fails**

Run: `mvn -q "-Dtest=ActuatorSurfaceIntegrationTest" test`

Expected: FAIL with `404` for `/actuator/health` or missing Actuator classes because the starter is not yet on the classpath and the management endpoints are not exposed.

- [ ] **Step 3: Add Actuator dependencies and management endpoint configuration**

In `pom.xml`, add:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

In `src/main/resources/application.yml`, add:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
```

- [ ] **Step 4: Run the actuator integration test to verify it passes**

Run: `mvn -q "-Dtest=ActuatorSurfaceIntegrationTest" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/test/java/com/yunye/mncdc/ops/ActuatorSurfaceIntegrationTest.java
git commit -m "feat: expose actuator management surface"
```


### Task 2: Add Store Read APIs For Ops Queries

**Files:**
- Modify: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\mapper\FullSyncTaskMapper.java`
- Modify: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\checkpoint\FullSyncTaskStore.java`
- Modify: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\mapper\RebuildTaskMapper.java`
- Modify: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\ddl\RebuildTaskStore.java`
- Modify: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\mapper\SchemaStateMapper.java`
- Modify: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\ddl\SchemaStateStore.java`
- Modify: `C:\javapractice\mn-CDC\src\test\java\com\yunye\mncdc\checkpoint\FullSyncTaskStoreTest.java`
- Modify: `C:\javapractice\mn-CDC\src\test\java\com\yunye\mncdc\ddl\RebuildTaskStoreTest.java`
- Modify: `C:\javapractice\mn-CDC\src\test\java\com\yunye\mncdc\ddl\SchemaStateStoreTest.java`

- [ ] **Step 1: Write failing store tests for recent-task and summary reads**

Add these tests to the existing store test classes:

```java
@Test
void loadsRecentFullSyncTasks() {
    when(fullSyncTaskMapper.selectRecent(5)).thenReturn(List.of(entity));

    assertThat(store.loadRecent(5)).extracting(FullSyncTask::tableName).containsExactly("user");
}
```

```java
@Test
void loadsRecentRebuildTasksFilteredByStatus() {
    when(rebuildTaskMapper.selectRecent("FAILED", 10)).thenReturn(List.of(entity));

    assertThat(store.loadRecent("FAILED", 10)).extracting(RebuildTask::status).containsExactly("FAILED");
}

@Test
void countsRebuildTasksByStatus() {
    when(rebuildTaskMapper.countByStatus("RUNNING")).thenReturn(2L);

    assertThat(store.countByStatus("RUNNING")).isEqualTo(2L);
}
```

```java
@Test
void loadsAllSchemaStates() {
    when(schemaStateMapper.selectAll()).thenReturn(List.of(entity));

    assertThat(store.loadAll()).extracting(SchemaState::tableName).containsExactly("user");
}
```

- [ ] **Step 2: Run the store tests to verify they fail**

Run: `mvn -q "-Dtest=FullSyncTaskStoreTest,RebuildTaskStoreTest,SchemaStateStoreTest" test`

Expected: FAIL because the read-query mapper methods and store wrappers do not yet exist.

- [ ] **Step 3: Add mapper SQL for recent-task and summary queries**

Add the following SQL methods.

In `FullSyncTaskMapper`:

```java
@Select("""
        SELECT connector_name, database_name, table_name, status, cutover_binlog_filename, cutover_binlog_position,
               last_sent_pk, started_at, finished_at, last_error
        FROM full_sync_task
        ORDER BY updated_at DESC
        LIMIT #{limit}
        """)
List<FullSyncTaskEntity> selectRecent(@Param("limit") int limit);
```

In `RebuildTaskMapper`:

```java
@Select("""
        <script>
        SELECT task_id, database_name, table_name, schema_binlog_file, schema_next_position, status, retry_count, last_error
        FROM rebuild_task
        <if test="status != null and status != ''">
          WHERE status = #{status}
        </if>
        ORDER BY updated_at DESC
        LIMIT #{limit}
        </script>
        """)
List<RebuildTaskEntity> selectRecent(@Param("status") String status, @Param("limit") int limit);

@Select("""
        SELECT COUNT(1)
        FROM rebuild_task
        WHERE status = #{status}
        """)
long countByStatus(@Param("status") String status);
```

In `SchemaStateMapper`:

```java
@Select("""
        SELECT table_key, database_name, table_name, status, schema_binlog_file, schema_next_position, ddl_type, ddl_sql
        FROM schema_state
        ORDER BY database_name ASC, table_name ASC
        """)
List<SchemaStateEntity> selectAll();
```

- [ ] **Step 4: Add store wrappers that map these reads into domain models**

Add these methods.

In `FullSyncTaskStore`:

```java
public List<FullSyncTask> loadRecent(int limit) {
    try {
        return fullSyncTaskMapper.selectRecent(limit).stream()
                .map(this::toModel)
                .toList();
    } catch (PersistenceException | DataAccessException exception) {
        throw new IllegalStateException("Failed to load full sync tasks.", exception);
    }
}
```

In `RebuildTaskStore`:

```java
public List<RebuildTask> loadRecent(String status, int limit) {
    try {
        return rebuildTaskMapper.selectRecent(status, limit).stream()
                .map(this::toModel)
                .toList();
    } catch (PersistenceException | DataAccessException exception) {
        throw new IllegalStateException("Failed to load rebuild tasks.", exception);
    }
}

public long countByStatus(String status) {
    try {
        return rebuildTaskMapper.countByStatus(status);
    } catch (PersistenceException | DataAccessException exception) {
        throw new IllegalStateException("Failed to count rebuild tasks.", exception);
    }
}
```

In `SchemaStateStore`:

```java
public List<SchemaState> loadAll() {
    try {
        return schemaStateMapper.selectAll().stream()
                .map(this::toModel)
                .toList();
    } catch (PersistenceException | DataAccessException exception) {
        throw new IllegalStateException("Failed to load schema states.", exception);
    }
}
```

Also add a private `toModel(FullSyncTaskEntity entity)` helper in `FullSyncTaskStore` so `loadRecent` does not duplicate mapping logic.

- [ ] **Step 5: Run the store tests to verify they pass**

Run: `mvn -q "-Dtest=FullSyncTaskStoreTest,RebuildTaskStoreTest,SchemaStateStoreTest" test`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yunye/mncdc/mapper/FullSyncTaskMapper.java src/main/java/com/yunye/mncdc/checkpoint/FullSyncTaskStore.java src/main/java/com/yunye/mncdc/mapper/RebuildTaskMapper.java src/main/java/com/yunye/mncdc/ddl/RebuildTaskStore.java src/main/java/com/yunye/mncdc/mapper/SchemaStateMapper.java src/main/java/com/yunye/mncdc/ddl/SchemaStateStore.java src/test/java/com/yunye/mncdc/checkpoint/FullSyncTaskStoreTest.java src/test/java/com/yunye/mncdc/ddl/RebuildTaskStoreTest.java src/test/java/com/yunye/mncdc/ddl/SchemaStateStoreTest.java
git commit -m "feat: add ops query reads for task and schema state"
```


### Task 3: Add Recent Event Buffer And Metrics Recorder

**Files:**
- Create: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\ops\RecentEventSummary.java`
- Create: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\ops\RecentEventBuffer.java`
- Create: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\ops\CdcObservabilityService.java`
- Create: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\ops\RebuildTaskMetricsBinder.java`
- Modify: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\checkpoint\CheckpointStore.java`
- Modify: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\cdc\CdcEventPublisher.java`
- Modify: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\redis\RedisSyncConsumer.java`
- Modify: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\ddl\SchemaChangeMessageHandler.java`
- Modify: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\ddl\RedisTableRebuildWorker.java`
- Create: `C:\javapractice\mn-CDC\src\test\java\com\yunye\mncdc\ops\RecentEventBufferTest.java`
- Create: `C:\javapractice\mn-CDC\src\test\java\com\yunye\mncdc\ops\CdcObservabilityServiceTest.java`
- Modify: `C:\javapractice\mn-CDC\src\test\java\com\yunye\mncdc\cdc\CdcEventPublisherTest.java`
- Modify: `C:\javapractice\mn-CDC\src\test\java\com\yunye\mncdc\redis\RedisSyncConsumerTest.java`
- Modify: `C:\javapractice\mn-CDC\src\test\java\com\yunye\mncdc\ddl\SchemaChangeMessageHandlerTest.java`
- Modify: `C:\javapractice\mn-CDC\src\test\java\com\yunye\mncdc\ddl\RedisTableRebuildWorkerTest.java`

- [ ] **Step 1: Write failing tests for bounded recent events and metric/event recording**

Create `RecentEventBufferTest`:

```java
@Test
void keepsOnlyNewestConfiguredEntries() {
    RecentEventBuffer buffer = new RecentEventBuffer(2);
    buffer.append(new RecentEventSummary(Instant.parse("2026-04-16T12:00:00Z"), "DML_TX", "mini", "user", "id=1", "SUCCESS", "applied"));
    buffer.append(new RecentEventSummary(Instant.parse("2026-04-16T12:00:01Z"), "DML_TX", "mini", "user", "id=2", "SUCCESS", "applied"));
    buffer.append(new RecentEventSummary(Instant.parse("2026-04-16T12:00:02Z"), "DDL", "mini", "user", "ddl:mysql-bin.000001:9", "TRIGGERED_REBUILD", "drop column age"));

    assertThat(buffer.snapshot()).extracting(RecentEventSummary::reference)
            .containsExactly("ddl:mysql-bin.000001:9", "id=2");
}
```

Create `CdcObservabilityServiceTest`:

```java
@Test
void recordsPublishedSnapshotMetricsAndRecentEvent() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RecentEventBuffer buffer = new RecentEventBuffer(10);
    CdcObservabilityService service = new CdcObservabilityService(registry, buffer);

    service.recordSnapshotPublished("mini", "user", "snapshot:mini:user:0", 3);

    assertThat(registry.get("cdc_events_published_total").counter().count()).isEqualTo(1.0d);
    assertThat(registry.get("cdc_snapshot_rows_sent_total").counter().count()).isEqualTo(3.0d);
    assertThat(buffer.snapshot()).singleElement().extracting(RecentEventSummary::eventType).isEqualTo("SNAPSHOT_ROW");
}
```

Extend the existing publisher, consumer, schema-change, and rebuild-worker tests with verifications that the recorder methods are called.

- [ ] **Step 2: Run the new and updated tests to verify they fail**

Run: `mvn -q "-Dtest=RecentEventBufferTest,CdcObservabilityServiceTest,CdcEventPublisherTest,RedisSyncConsumerTest,SchemaChangeMessageHandlerTest,RedisTableRebuildWorkerTest" test`

Expected: FAIL because the buffer, recorder, and injected observability hooks do not yet exist.

- [ ] **Step 3: Create the bounded event buffer and centralized observability service**

Add `RecentEventSummary`:

```java
package com.yunye.mncdc.ops;

import java.time.Instant;

public record RecentEventSummary(
        Instant timestamp,
        String eventType,
        String databaseName,
        String tableName,
        String reference,
        String result,
        String message
) {
}
```

Add `RecentEventBuffer`:

```java
package com.yunye.mncdc.ops;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

@Component
public class RecentEventBuffer {

    private static final int DEFAULT_CAPACITY = 50;

    private final int capacity;
    private final Deque<RecentEventSummary> events = new ArrayDeque<>();

    public RecentEventBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public RecentEventBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void append(RecentEventSummary event) {
        if (events.size() == capacity) {
            events.removeLast();
        }
        events.addFirst(event);
    }

    public synchronized List<RecentEventSummary> snapshot() {
        return List.copyOf(events);
    }
}
```

Add `CdcObservabilityService`:

```java
@Service
public class CdcObservabilityService {

    private final MeterRegistry meterRegistry;
    private final RecentEventBuffer recentEventBuffer;

    public CdcObservabilityService(MeterRegistry meterRegistry, RecentEventBuffer recentEventBuffer) {
        this.meterRegistry = meterRegistry;
        this.recentEventBuffer = recentEventBuffer;
    }

    public void recordCheckpointSaved(String connectorName, String filename, Long position) {
        meterRegistry.counter("cdc_checkpoint_save_total").increment();
        recentEventBuffer.append(new RecentEventSummary(
                Instant.now(), "CHECKPOINT", null, null, connectorName, "SAVED", filename + ":" + position
        ));
    }

    public void recordTransactionPublished(String databaseName, String tableName, String transactionId) {
        meterRegistry.counter("cdc_events_published_total").increment();
        recentEventBuffer.append(new RecentEventSummary(
                Instant.now(), "DML_TX", databaseName, tableName, transactionId, "PUBLISHED", "published to kafka"
        ));
    }

    public void recordSnapshotPublished(String databaseName, String tableName, String transactionId, int rowCount) {
        meterRegistry.counter("cdc_events_published_total").increment();
        meterRegistry.counter("cdc_snapshot_rows_sent_total").increment(rowCount);
        recentEventBuffer.append(new RecentEventSummary(
                Instant.now(), "SNAPSHOT_ROW", databaseName, tableName, transactionId, "PUBLISHED", "rows=" + rowCount
        ));
    }

    public void recordSchemaChangeAccepted(String databaseName, String tableName, String eventId, String ddlType) {
        recentEventBuffer.append(new RecentEventSummary(
                Instant.now(), "DDL", databaseName, tableName, eventId, "TRIGGERED_REBUILD", ddlType
        ));
    }

    public void recordTransactionApplied(String databaseName, String tableName, String transactionId) {
        meterRegistry.counter("cdc_events_consumed_total").increment();
        recentEventBuffer.append(new RecentEventSummary(
                Instant.now(), "DML_TX", databaseName, tableName, transactionId, "SUCCESS", "applied to redis"
        ));
    }

    public void recordTransactionBuffered(String databaseName, String tableName, String transactionId) {
        meterRegistry.counter("cdc_events_consumed_total").increment();
        recentEventBuffer.append(new RecentEventSummary(
                Instant.now(), "DML_TX", databaseName, tableName, transactionId, "BUFFERED", "blocked by rebuild"
        ));
    }

    public void recordTransactionFailed(String databaseName, String tableName, String transactionId, String message) {
        meterRegistry.counter("cdc_events_failed_total").increment();
        recentEventBuffer.append(new RecentEventSummary(
                Instant.now(), "DML_TX", databaseName, tableName, transactionId, "FAILED", message
        ));
    }

    public long recordRebuildStarted(String databaseName, String tableName, String taskId) {
        meterRegistry.counter("cdc_rebuild_started_total").increment();
        recentEventBuffer.append(new RecentEventSummary(
                Instant.now(), "REBUILD", databaseName, tableName, taskId, "STARTED", "rebuild started"
        ));
        return System.nanoTime();
    }

    public void recordRebuildCompleted(String databaseName, String tableName, String taskId, long startedAtNanos) {
        meterRegistry.counter("cdc_rebuild_completed_total").increment();
        meterRegistry.timer("cdc_rebuild_duration").record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
        recentEventBuffer.append(new RecentEventSummary(
                Instant.now(), "REBUILD", databaseName, tableName, taskId, "SUCCESS", "rebuild completed"
        ));
    }

    public void recordRebuildFailed(String databaseName, String tableName, String taskId, String message) {
        meterRegistry.counter("cdc_rebuild_failed_total").increment();
        recentEventBuffer.append(new RecentEventSummary(
                Instant.now(), "REBUILD", databaseName, tableName, taskId, "FAILED", message
        ));
    }
}
```

Back each method with:

```java
meterRegistry.counter("cdc_events_published_total").increment();
recentEventBuffer.append(new RecentEventSummary(Instant.now(), "DML_TX", databaseName, tableName, transactionId, "SUCCESS", "published to kafka"));
```

Add `RebuildTaskMetricsBinder`:

```java
@Component
public class RebuildTaskMetricsBinder {

    public RebuildTaskMetricsBinder(MeterRegistry meterRegistry, RebuildTaskStore rebuildTaskStore) {
        Gauge.builder("cdc_pending_rebuild_tasks", rebuildTaskStore, store -> (double) store.countByStatus("PENDING"))
                .register(meterRegistry);
        Gauge.builder("cdc_failed_rebuild_tasks", rebuildTaskStore, store -> (double) store.countByStatus("FAILED"))
                .register(meterRegistry);
    }
}
```

- [ ] **Step 4: Wire the recorder into existing runtime checkpoints**

Modify runtime classes to call the recorder at the existing success/failure points.

In the `CheckpointStore.save` path:

```java
checkpointMapper.insertOrUpdate(toEntity(checkpoint));
observabilityService.recordCheckpointSaved(
        checkpoint.connectorName(),
        checkpoint.binlogFilename(),
        checkpoint.binlogPosition()
);
```

In `CdcEventPublisher.publishTransaction` and `publishSnapshotPage`:

```java
CompletableFuture<SendResult<String, String>> future =
        publishPayload(transactionEvent.transactionId(), CdcMessageEnvelope.transaction(transactionEvent));
observabilityService.recordTransactionPublished(
        transactionEvent.events().get(0).database(),
        transactionEvent.events().get(0).table(),
        transactionEvent.transactionId()
);
return future;
```

In the `RedisSyncConsumer.consume` success path:

```java
if (result == TransactionRoutingService.RouteResult.APPLIED) {
    CdcTransactionRow first = envelope.transaction().events().get(0);
    observabilityService.recordTransactionApplied(first.database(), first.table(), envelope.transaction().transactionId());
}
if (result == TransactionRoutingService.RouteResult.BUFFERED) {
    CdcTransactionRow first = envelope.transaction().events().get(0);
    observabilityService.recordTransactionBuffered(first.database(), first.table(), envelope.transaction().transactionId());
}
```

In the `catch` block:

```java
if (envelope.transaction() != null && envelope.transaction().events() != null && !envelope.transaction().events().isEmpty()) {
    CdcTransactionRow first = envelope.transaction().events().get(0);
    observabilityService.recordTransactionFailed(first.database(), first.table(), envelope.transaction().transactionId(), exception.getMessage());
}
```

In the `SchemaChangeMessageHandler.handle` success path:

```java
observabilityService.recordSchemaChangeAccepted(
        event.database(),
        event.table(),
        event.eventId(),
        event.ddlType()
);
```

Add these exact insertions inside `RedisTableRebuildWorker`:

```java
long startedAtNanos = observabilityService.recordRebuildStarted(task.databaseName(), task.tableName(), task.taskId());
```

Add just before `rebuildTaskStore.markDone(task.taskId());`:

```java
observabilityService.recordRebuildCompleted(task.databaseName(), task.tableName(), task.taskId(), startedAtNanos);
```

Add as the first line inside `handleFailure(RebuildTask task, Exception exception)` after `errorMessage` is computed:

```java
observabilityService.recordRebuildFailed(task.databaseName(), task.tableName(), task.taskId(), errorMessage);
```

- [ ] **Step 5: Run the observability unit slice to verify it passes**

Run: `mvn -q "-Dtest=RecentEventBufferTest,CdcObservabilityServiceTest,CdcEventPublisherTest,RedisSyncConsumerTest,SchemaChangeMessageHandlerTest,RedisTableRebuildWorkerTest" test`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yunye/mncdc/ops/RecentEventSummary.java src/main/java/com/yunye/mncdc/ops/RecentEventBuffer.java src/main/java/com/yunye/mncdc/ops/CdcObservabilityService.java src/main/java/com/yunye/mncdc/ops/RebuildTaskMetricsBinder.java src/main/java/com/yunye/mncdc/checkpoint/CheckpointStore.java src/main/java/com/yunye/mncdc/cdc/CdcEventPublisher.java src/main/java/com/yunye/mncdc/redis/RedisSyncConsumer.java src/main/java/com/yunye/mncdc/ddl/SchemaChangeMessageHandler.java src/main/java/com/yunye/mncdc/ddl/RedisTableRebuildWorker.java src/test/java/com/yunye/mncdc/ops/RecentEventBufferTest.java src/test/java/com/yunye/mncdc/ops/CdcObservabilityServiceTest.java src/test/java/com/yunye/mncdc/cdc/CdcEventPublisherTest.java src/test/java/com/yunye/mncdc/redis/RedisSyncConsumerTest.java src/test/java/com/yunye/mncdc/ddl/SchemaChangeMessageHandlerTest.java src/test/java/com/yunye/mncdc/ddl/RedisTableRebuildWorkerTest.java
git commit -m "feat: add cdc metrics and recent event recorder"
```


### Task 4: Add CDC Health Indicator And Read-Only Ops API

**Files:**
- Create: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\ops\CdcHealthIndicator.java`
- Create: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\ops\OpsQueryService.java`
- Create: `C:\javapractice\mn-CDC\src\main\java\com\yunye\mncdc\ops\OpsController.java`
- Create: `C:\javapractice\mn-CDC\src\test\java\com\yunye\mncdc\ops\CdcHealthIndicatorTest.java`
- Create: `C:\javapractice\mn-CDC\src\test\java\com\yunye\mncdc\ops\OpsQueryServiceTest.java`
- Create: `C:\javapractice\mn-CDC\src\test\java\com\yunye\mncdc\ops\OpsControllerTest.java`

- [ ] **Step 1: Write failing tests for CDC health details, overview aggregation, and controller routes**

Create `CdcHealthIndicatorTest`:

```java
@Test
void reportsConnectorRuntimeAndRebuildCounts() {
    when(properties.isEnabled()).thenReturn(true);
    when(properties.getCheckpoint()).thenReturn(checkpointProperties);
    when(checkpointProperties.getConnectorName()).thenReturn("mini-user-sync");
    when(checkpointProperties.getStartupStrategy()).thenReturn(MiniCdcProperties.StartupStrategy.SNAPSHOT_THEN_INCREMENTAL);
    when(checkpointStore.load()).thenReturn(Optional.of(new BinlogCheckpoint("mini-user-sync", "mysql-bin.000001", 128L)));
    when(rebuildTaskStore.countByStatus("RUNNING")).thenReturn(1L);
    when(rebuildTaskStore.countByStatus("FAILED")).thenReturn(2L);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("connectorName", "mini-user-sync");
    assertThat(health.getDetails()).containsEntry("runningRebuildCount", 1L);
    assertThat(health.getDetails()).containsEntry("failedRebuildCount", 2L);
}
```

Create `OpsQueryServiceTest`:

```java
@Test
void buildsOverviewFromStoresAndRecentEvents() {
    when(checkpointStore.load()).thenReturn(Optional.of(new BinlogCheckpoint("mini-user-sync", "mysql-bin.000001", 128L)));
    when(fullSyncTaskStore.loadRecent(5)).thenReturn(List.of(task));
    when(rebuildTaskStore.loadRecent(null, 10)).thenReturn(List.of(rebuildTask));
    when(rebuildTaskStore.countByStatus("PENDING")).thenReturn(1L);
    when(rebuildTaskStore.countByStatus("RUNNING")).thenReturn(0L);
    when(rebuildTaskStore.countByStatus("DONE")).thenReturn(3L);
    when(rebuildTaskStore.countByStatus("FAILED")).thenReturn(1L);
    when(rebuildTaskStore.countByStatus("OBSOLETE")).thenReturn(0L);
    when(schemaStateStore.loadAll()).thenReturn(List.of(schemaState));

    OpsQueryService.OverviewResponse overview = service.loadOverview();

    assertThat(overview.checkpoint().connectorName()).isEqualTo("mini-user-sync");
    assertThat(overview.rebuildSummary().failed()).isEqualTo(1L);
    assertThat(overview.recentEvents()).hasSize(1);
}
```

Create `OpsControllerTest` using standalone MockMvc:

```java
@Test
void servesOverviewAndRecentEvents() throws Exception {
    when(queryService.loadOverview()).thenReturn(overview);
    when(queryService.loadRecentEvents()).thenReturn(List.of(event));

    mockMvc.perform(get("/ops/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.checkpoint.connectorName").value("mini-user-sync"));

    mockMvc.perform(get("/ops/events/recent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].eventType").value("DML_TX"));
}
```

- [ ] **Step 2: Run the new ops tests to verify they fail**

Run: `mvn -q "-Dtest=CdcHealthIndicatorTest,OpsQueryServiceTest,OpsControllerTest" test`

Expected: FAIL because the health indicator, query service, and controller do not yet exist.

- [ ] **Step 3: Implement the health indicator and overview query service**

Add `CdcHealthIndicator`:

```java
@Component("cdc")
@RequiredArgsConstructor
public class CdcHealthIndicator implements HealthIndicator {

    private final MiniCdcProperties properties;
    private final CheckpointStore checkpointStore;
    private final RebuildTaskStore rebuildTaskStore;
    private final ObjectProvider<BinlogCdcLifecycle> lifecycleProvider;

    @Override
    public Health health() {
        Optional<BinlogCheckpoint> checkpoint = checkpointStore.load();
        BinlogCdcLifecycle lifecycle = lifecycleProvider.getIfAvailable();
        return Health.up()
                .withDetail("runtimeEnabled", properties.isEnabled())
                .withDetail("connectorName", properties.getCheckpoint().getConnectorName())
                .withDetail("startupStrategy", properties.getCheckpoint().getStartupStrategy().name())
                .withDetail("checkpointPresent", checkpoint.isPresent())
                .withDetail("lifecycleRunning", lifecycle != null && lifecycle.isRunning())
                .withDetail("runningRebuildCount", rebuildTaskStore.countByStatus("RUNNING"))
                .withDetail("failedRebuildCount", rebuildTaskStore.countByStatus("FAILED"))
                .build();
    }
}
```

Add `OpsQueryService` with nested response records:

```java
@Service
@RequiredArgsConstructor
public class OpsQueryService {

    private final MiniCdcProperties properties;
    private final CheckpointStore checkpointStore;
    private final FullSyncTaskStore fullSyncTaskStore;
    private final RebuildTaskStore rebuildTaskStore;
    private final SchemaStateStore schemaStateStore;
    private final RecentEventBuffer recentEventBuffer;

    public OverviewResponse loadOverview() {
        return new OverviewResponse(
                loadCheckpoint(),
                fullSyncTaskStore.loadRecent(5),
                new RebuildSummaryResponse(
                        rebuildTaskStore.countByStatus("PENDING"),
                        rebuildTaskStore.countByStatus("RUNNING"),
                        rebuildTaskStore.countByStatus("DONE"),
                        rebuildTaskStore.countByStatus("FAILED"),
                        rebuildTaskStore.countByStatus("OBSOLETE")
                ),
                schemaStateStore.loadAll(),
                recentEventBuffer.snapshot()
        );
    }

    public CheckpointResponse loadCheckpoint() {
        Optional<BinlogCheckpoint> checkpoint = checkpointStore.load();
        return new CheckpointResponse(
                properties.getCheckpoint().getConnectorName(),
                properties.getCheckpoint().getStartupStrategy().name(),
                checkpoint.map(BinlogCheckpoint::binlogFilename).orElse(null),
                checkpoint.map(BinlogCheckpoint::binlogPosition).orElse(null),
                checkpoint.isPresent()
        );
    }

    public List<FullSyncTask> loadFullSyncTasks() { return fullSyncTaskStore.loadRecent(10); }
    public List<RebuildTask> loadRebuildTasks(String status) { return rebuildTaskStore.loadRecent(status, 20); }
    public RebuildSummaryResponse loadRebuildSummary() {
        return new RebuildSummaryResponse(
                rebuildTaskStore.countByStatus("PENDING"),
                rebuildTaskStore.countByStatus("RUNNING"),
                rebuildTaskStore.countByStatus("DONE"),
                rebuildTaskStore.countByStatus("FAILED"),
                rebuildTaskStore.countByStatus("OBSOLETE")
        );
    }

    public List<SchemaState> loadSchemaStates() { return schemaStateStore.loadAll(); }
    public List<RecentEventSummary> loadRecentEvents() { return recentEventBuffer.snapshot(); }

    public record OverviewResponse(CheckpointResponse checkpoint, List<FullSyncTask> fullSyncTasks,
                                   RebuildSummaryResponse rebuildSummary, List<SchemaState> schemaStates,
                                   List<RecentEventSummary> recentEvents) { }

    public record CheckpointResponse(String connectorName, String startupStrategy,
                                     String binlogFilename, Long binlogPosition, boolean present) { }

    public record RebuildSummaryResponse(long pending, long running, long done, long failed, long obsolete) { }
}
```

- [ ] **Step 4: Implement the read-only controller routes**

Add `OpsController`:

```java
@RestController
@RequestMapping("/ops")
@RequiredArgsConstructor
public class OpsController {

    private final OpsQueryService queryService;

    @GetMapping("/overview")
    public OpsQueryService.OverviewResponse overview() {
        return queryService.loadOverview();
    }

    @GetMapping("/checkpoint/current")
    public OpsQueryService.CheckpointResponse checkpoint() {
        return queryService.loadCheckpoint();
    }

    @GetMapping("/full-sync/tasks")
    public List<FullSyncTask> fullSyncTasks() {
        return queryService.loadFullSyncTasks();
    }

    @GetMapping("/rebuild/tasks")
    public List<RebuildTask> rebuildTasks(@RequestParam(required = false) String status) {
        return queryService.loadRebuildTasks(status);
    }

    @GetMapping("/rebuild/summary")
    public OpsQueryService.RebuildSummaryResponse rebuildSummary() {
        return queryService.loadRebuildSummary();
    }

    @GetMapping("/schema-state")
    public List<SchemaState> schemaState() {
        return queryService.loadSchemaStates();
    }

    @GetMapping("/events/recent")
    public List<RecentEventSummary> recentEvents() {
        return queryService.loadRecentEvents();
    }
}
```

- [ ] **Step 5: Run the ops tests to verify they pass**

Run: `mvn -q "-Dtest=CdcHealthIndicatorTest,OpsQueryServiceTest,OpsControllerTest" test`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yunye/mncdc/ops/CdcHealthIndicator.java src/main/java/com/yunye/mncdc/ops/OpsQueryService.java src/main/java/com/yunye/mncdc/ops/OpsController.java src/test/java/com/yunye/mncdc/ops/CdcHealthIndicatorTest.java src/test/java/com/yunye/mncdc/ops/OpsQueryServiceTest.java src/test/java/com/yunye/mncdc/ops/OpsControllerTest.java
git commit -m "feat: add cdc ops health indicator and api"
```


### Task 5: Build The Same-Repo Read-Only Dashboard

**Files:**
- Create: `C:\javapractice\mn-CDC\src\main\resources\static\ops-dashboard\index.html`
- Create: `C:\javapractice\mn-CDC\src\main\resources\static\ops-dashboard\ops-dashboard.css`
- Create: `C:\javapractice\mn-CDC\src\main\resources\static\ops-dashboard\ops-dashboard.js`
- Create: `C:\javapractice\mn-CDC\src\test\java\com\yunye\mncdc\ops\OpsDashboardResourceTest.java`

- [ ] **Step 1: Write the failing dashboard resource test**

Create `OpsDashboardResourceTest`:

```java
package com.yunye.mncdc.ops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mini-cdc.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:dashboard-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class OpsDashboardResourceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KafkaAdmin kafkaAdmin;

    @Test
    void servesDashboardIndex() throws Exception {
        mockMvc.perform(get("/ops-dashboard/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CDC Ops Dashboard")));
    }
}
```

- [ ] **Step 2: Run the dashboard resource test to verify it fails**

Run: `mvn -q "-Dtest=OpsDashboardResourceTest" test`

Expected: FAIL with `404` because the static dashboard files do not yet exist.

- [ ] **Step 3: Create the dashboard HTML, CSS, and JavaScript**

In `index.html`, add:

```html
<!doctype html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>CDC Ops Dashboard</title>
    <link rel="stylesheet" href="/ops-dashboard/ops-dashboard.css">
</head>
<body>
<main class="dashboard">
    <header class="hero">
        <h1>CDC Ops Dashboard</h1>
        <p id="last-refresh">Waiting for first refresh</p>
    </header>
    <section id="health-card" class="card"><h2>Health</h2><div class="card-body"></div></section>
    <section id="checkpoint-card" class="card"><h2>Checkpoint</h2><div class="card-body"></div></section>
    <section id="task-card" class="card"><h2>Task Status</h2><div class="card-body"></div></section>
    <section id="schema-card" class="card"><h2>Schema State</h2><div class="card-body"></div></section>
    <section id="events-card" class="card"><h2>Recent Events</h2><div class="card-body"></div></section>
    <div id="error-banner" class="error hidden"></div>
</main>
<script src="/ops-dashboard/ops-dashboard.js"></script>
</body>
</html>
```

In `ops-dashboard.css`, add:

```css
:root {
    --bg: linear-gradient(135deg, #f7f3ea 0%, #efe2c1 100%);
    --card: rgba(255, 255, 255, 0.88);
    --ink: #1f2a37;
    --accent: #8a4b08;
    --danger: #a61b29;
    --border: rgba(31, 42, 55, 0.12);
}

body {
    margin: 0;
    font-family: "Segoe UI", "PingFang SC", sans-serif;
    color: var(--ink);
    background: var(--bg);
}

.dashboard {
    max-width: 1200px;
    margin: 0 auto;
    padding: 24px;
    display: grid;
    gap: 16px;
}

.card {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 16px;
}

.hidden {
    display: none;
}
```

In `ops-dashboard.js`, add:

```javascript
const refreshIntervalMs = 5000;

async function loadOverview() {
  const response = await fetch("/ops/overview");
  if (!response.ok) {
    throw new Error(`overview request failed: ${response.status}`);
  }
  return response.json();
}

function renderOverview(data) {
  document.querySelector("#checkpoint-card .card-body").textContent =
    `${data.checkpoint.connectorName} @ ${data.checkpoint.binlogFilename}:${data.checkpoint.binlogPosition}`;
  document.querySelector("#task-card .card-body").textContent =
    `pending=${data.rebuildSummary.pending}, running=${data.rebuildSummary.running}, failed=${data.rebuildSummary.failed}`;
  document.querySelector("#schema-card .card-body").innerHTML =
    data.schemaStates.map(state => `<div>${state.databaseName}.${state.tableName} - ${state.status}</div>`).join("");
  document.querySelector("#events-card .card-body").innerHTML =
    data.recentEvents.map(event => `<div>${event.timestamp} ${event.eventType} ${event.reference} ${event.result}</div>`).join("");
  document.querySelector("#last-refresh").textContent = `Last refresh: ${new Date().toLocaleString()}`;
}

async function refresh() {
  try {
    const data = await loadOverview();
    renderOverview(data);
    document.getElementById("error-banner").classList.add("hidden");
  } catch (error) {
    const banner = document.getElementById("error-banner");
    banner.textContent = error.message;
    banner.classList.remove("hidden");
  }
}

refresh();
setInterval(refresh, refreshIntervalMs);
```

- [ ] **Step 4: Run the dashboard resource test to verify it passes**

Run: `mvn -q "-Dtest=OpsDashboardResourceTest" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/ops-dashboard/index.html src/main/resources/static/ops-dashboard/ops-dashboard.css src/main/resources/static/ops-dashboard/ops-dashboard.js src/test/java/com/yunye/mncdc/ops/OpsDashboardResourceTest.java
git commit -m "feat: add read-only cdc ops dashboard"
```


### Task 6: Add Full-Slice Integration Coverage And Verification

**Files:**
- Create: `C:\javapractice\mn-CDC\src\test\java\com\yunye\mncdc\ops\OpsSurfaceIntegrationTest.java`
- Modify: `C:\javapractice\mn-CDC\docs\superpowers\plans\2026-04-16-observability-ops-dashboard.md`

- [ ] **Step 1: Write the failing full-slice integration test**

Create `OpsSurfaceIntegrationTest`:

```java
package com.yunye.mncdc.ops;

import com.yunye.mncdc.checkpoint.CheckpointStore;
import com.yunye.mncdc.checkpoint.FullSyncTaskStore;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.ddl.RebuildTaskStore;
import com.yunye.mncdc.ddl.SchemaStateStore;
import com.yunye.mncdc.model.BinlogCheckpoint;
import com.yunye.mncdc.model.FullSyncTask;
import com.yunye.mncdc.model.FullSyncTaskStatus;
import com.yunye.mncdc.model.RebuildTask;
import com.yunye.mncdc.model.SchemaState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mini-cdc.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ops-surface-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class OpsSurfaceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecentEventBuffer recentEventBuffer;

    @MockitoBean
    private KafkaAdmin kafkaAdmin;

    @MockitoBean
    private CheckpointStore checkpointStore;

    @MockitoBean
    private FullSyncTaskStore fullSyncTaskStore;

    @MockitoBean
    private RebuildTaskStore rebuildTaskStore;

    @MockitoBean
    private SchemaStateStore schemaStateStore;

    @MockitoBean
    private MiniCdcProperties properties;

    @MockitoBean
    private MiniCdcProperties.Checkpoint checkpointProperties;

    @Test
    void servesActuatorOverviewAndRecentEvents() throws Exception {
        when(properties.isEnabled()).thenReturn(false);
        when(properties.getCheckpoint()).thenReturn(checkpointProperties);
        when(checkpointProperties.getConnectorName()).thenReturn("mini-user-sync");
        when(checkpointProperties.getStartupStrategy()).thenReturn(MiniCdcProperties.StartupStrategy.SNAPSHOT_THEN_INCREMENTAL);
        when(checkpointStore.load()).thenReturn(Optional.of(new BinlogCheckpoint("mini-user-sync", "mysql-bin.000120", 456L)));
        when(fullSyncTaskStore.loadRecent(5)).thenReturn(List.of(new FullSyncTask("mini-user-sync", "mini", "user", FullSyncTaskStatus.RUNNING, "mysql-bin.000120", 456L, "{\"id\":9}", LocalDateTime.now(), null, null)));
        when(rebuildTaskStore.loadRecent(null, 20)).thenReturn(List.of(new RebuildTask("mini.user:mysql-bin.000120:456", "mini", "user", "mysql-bin.000120", 456L, "PENDING", 0, null)));
        when(rebuildTaskStore.countByStatus("PENDING")).thenReturn(1L);
        when(rebuildTaskStore.countByStatus("RUNNING")).thenReturn(0L);
        when(rebuildTaskStore.countByStatus("DONE")).thenReturn(0L);
        when(rebuildTaskStore.countByStatus("FAILED")).thenReturn(0L);
        when(rebuildTaskStore.countByStatus("OBSOLETE")).thenReturn(0L);
        when(schemaStateStore.loadAll()).thenReturn(List.of(new SchemaState("mini", "user", "ACTIVE", "mysql-bin.000120", 456L, "ADD_COLUMN", "ALTER TABLE user ADD COLUMN age INT")));

        recentEventBuffer.append(new RecentEventSummary(Instant.parse("2026-04-16T12:00:00Z"), "DML_TX", "mini", "user", "id=9", "SUCCESS", "applied"));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.cdc.details.connectorName").value("mini-user-sync"));

        mockMvc.perform(get("/ops/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoint.binlogFilename").value("mysql-bin.000120"))
                .andExpect(jsonPath("$.rebuildSummary.pending").value(1))
                .andExpect(jsonPath("$.recentEvents[0].reference").value("id=9"));
    }
}
```

- [ ] **Step 2: Run the full-slice integration test to verify it fails**

Run: `mvn -q "-Dtest=OpsSurfaceIntegrationTest" test`

Expected: FAIL because at least one of the health, query, or serialization paths is still missing or mismatched.

- [ ] **Step 3: Fix any serialization, bean wiring, or response-shape mismatches found by the integration test**

Use these concrete checks while fixing:

```java
@Component("cdc")
public class CdcHealthIndicator implements HealthIndicator {
}
```

```java
public record OverviewResponse(
        CheckpointResponse checkpoint,
        List<FullSyncTask> fullSyncTasks,
        RebuildSummaryResponse rebuildSummary,
        List<SchemaState> schemaStates,
        List<RecentEventSummary> recentEvents
) {
}
```

```java
public record RebuildSummaryResponse(long pending, long running, long done, long failed, long obsolete) {
}
```

- [ ] **Step 4: Run the full verification slice**

Run:

```bash
mvn -q "-Dtest=ActuatorSurfaceIntegrationTest,FullSyncTaskStoreTest,RebuildTaskStoreTest,SchemaStateStoreTest,RecentEventBufferTest,CdcObservabilityServiceTest,CdcHealthIndicatorTest,OpsQueryServiceTest,OpsControllerTest,OpsDashboardResourceTest,OpsSurfaceIntegrationTest" test
```

Expected: PASS

- [ ] **Step 5: Review the dashboard manually**

Run:

```bash
mvn spring-boot:run
```

Then verify manually:

1. Open `http://localhost:8089/ops-dashboard/index.html`
2. Open `http://localhost:8089/actuator/health`
3. Open `http://localhost:8089/ops/overview`
4. Trigger a DML through MySQL or an existing test path
5. Refresh the dashboard and confirm the checkpoint/task/event sections move

- [ ] **Step 6: Update the plan checklist with actual outcomes and commit**

```bash
git add src/test/java/com/yunye/mncdc/ops/OpsSurfaceIntegrationTest.java docs/superpowers/plans/2026-04-16-observability-ops-dashboard.md
git commit -m "test: verify observability ops surface"
```
