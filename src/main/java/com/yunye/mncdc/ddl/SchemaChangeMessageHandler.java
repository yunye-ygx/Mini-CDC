package com.yunye.mncdc.ddl;

import com.yunye.mncdc.model.CdcSchemaChangeEvent;
import com.yunye.mncdc.model.RebuildTask;
import com.yunye.mncdc.model.SchemaState;
import com.yunye.mncdc.ops.CdcObservabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SchemaChangeMessageHandler {

    private final SchemaStateStore schemaStateStore;
    private final RebuildTaskStore rebuildTaskStore;
    private final CdcObservabilityService observabilityService;

    public HandleResult handle(CdcSchemaChangeEvent event) {
        boolean rebuildRequired = eventRequiresRebuild(event);
        schemaStateStore.upsert(new SchemaState(
                event.database(),
                event.table(),
                rebuildRequired ? "REBUILD_REQUIRED" : "ACTIVE",
                event.binlogFilename(),
                event.nextPosition(),
                event.ddlType(),
                event.rawSql()
        ));
        if (rebuildRequired) {
            rebuildTaskStore.create(new RebuildTask(
                    buildTaskId(event),
                    event.database(),
                    event.table(),
                    event.binlogFilename(),
                    event.nextPosition(),
                    "PENDING",
                    0,
                    null
            ));
        }
        observabilityService.recordSchemaChangeAccepted(
                event.database(),
                event.table(),
                event.eventId(),
                event.ddlType()
        );
        return HandleResult.ACCEPTED;
    }

    private boolean eventRequiresRebuild(CdcSchemaChangeEvent event) {
        return event != null && switch (event.ddlType()) {
            case "DROP_COLUMN", "RENAME_COLUMN", "MODIFY_COLUMN" -> true;
            default -> false;
        };
    }

    private String buildTaskId(CdcSchemaChangeEvent event) {
        return event.database() + "." + event.table() + ":" + event.binlogFilename() + ":" + event.nextPosition();
    }

    public enum HandleResult {
        ACCEPTED
    }
}
