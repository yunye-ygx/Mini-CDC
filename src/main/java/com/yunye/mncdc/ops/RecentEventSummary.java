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
