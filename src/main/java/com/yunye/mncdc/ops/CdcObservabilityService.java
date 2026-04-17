package com.yunye.mncdc.ops;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

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
