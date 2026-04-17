package com.yunye.mncdc.ops;

import com.yunye.mncdc.cdc.BinlogCdcLifecycle;
import com.yunye.mncdc.checkpoint.CheckpointStore;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.ddl.RebuildTaskStore;
import com.yunye.mncdc.model.BinlogCheckpoint;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Optional;

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
