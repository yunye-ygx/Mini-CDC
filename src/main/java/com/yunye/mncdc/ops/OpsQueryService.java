package com.yunye.mncdc.ops;

import com.yunye.mncdc.checkpoint.CheckpointStore;
import com.yunye.mncdc.checkpoint.FullSyncTaskStore;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.ddl.RebuildTaskStore;
import com.yunye.mncdc.ddl.SchemaStateStore;
import com.yunye.mncdc.model.BinlogCheckpoint;
import com.yunye.mncdc.model.FullSyncTask;
import com.yunye.mncdc.model.RebuildTask;
import com.yunye.mncdc.model.SchemaState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

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

    public List<FullSyncTask> loadFullSyncTasks() {
        return fullSyncTaskStore.loadRecent(10);
    }

    public List<RebuildTask> loadRebuildTasks(String status) {
        return rebuildTaskStore.loadRecent(status, 20);
    }

    public RebuildSummaryResponse loadRebuildSummary() {
        return new RebuildSummaryResponse(
                rebuildTaskStore.countByStatus("PENDING"),
                rebuildTaskStore.countByStatus("RUNNING"),
                rebuildTaskStore.countByStatus("DONE"),
                rebuildTaskStore.countByStatus("FAILED"),
                rebuildTaskStore.countByStatus("OBSOLETE")
        );
    }

    public List<SchemaState> loadSchemaStates() {
        return schemaStateStore.loadAll();
    }

    public List<RecentEventSummary> loadRecentEvents() {
        return recentEventBuffer.snapshot();
    }

    public record OverviewResponse(
            CheckpointResponse checkpoint,
            List<FullSyncTask> fullSyncTasks,
            RebuildSummaryResponse rebuildSummary,
            List<SchemaState> schemaStates,
            List<RecentEventSummary> recentEvents
    ) {
    }

    public record CheckpointResponse(
            String connectorName,
            String startupStrategy,
            String binlogFilename,
            Long binlogPosition,
            boolean present
    ) {
    }

    public record RebuildSummaryResponse(long pending, long running, long done, long failed, long obsolete) {
    }
}
