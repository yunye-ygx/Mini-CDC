package com.yunye.mncdc.ddl;

import com.yunye.mncdc.config.MiniCdcProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class RebuildTaskScheduler {

    private final MiniCdcProperties properties;
    private final RebuildTaskStore rebuildTaskStore;
    private final RedisTableRebuildWorker worker;

    @Scheduled(fixedDelayString = "${mini-cdc.ddl.rebuild-poll-interval:PT5S}")
    public void poll() {
        Instant cutoff = Instant.now().minus(properties.getDdl().getRunningTimeout());
        rebuildTaskStore.resetExpiredRunningTasks(cutoff);
        rebuildTaskStore.claimNextPendingTask().ifPresent(worker::runTask);
    }
}
