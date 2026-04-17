package com.yunye.mncdc.ops;

import com.yunye.mncdc.ddl.RebuildTaskStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class RebuildTaskMetricsBinder {

    public RebuildTaskMetricsBinder(MeterRegistry meterRegistry, RebuildTaskStore rebuildTaskStore) {
        Gauge.builder("cdc_pending_rebuild_tasks", rebuildTaskStore, store -> (double) store.countByStatus("PENDING"))
                .register(meterRegistry);
        Gauge.builder("cdc_failed_rebuild_tasks", rebuildTaskStore, store -> (double) store.countByStatus("FAILED"))
                .register(meterRegistry);
    }
}
