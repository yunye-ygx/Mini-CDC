package com.yunye.mncdc.ops;

import com.yunye.mncdc.model.FullSyncTask;
import com.yunye.mncdc.model.RebuildTask;
import com.yunye.mncdc.model.SchemaState;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
