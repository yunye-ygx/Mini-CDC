package com.yunye.mncdc.model;

import java.time.LocalDateTime;

public record FullSyncTask(
        String connectorName,
        String databaseName,
        String tableName,
        FullSyncTaskStatus status,
        String cutoverBinlogFilename,
        Long cutoverBinlogPosition,
        String lastSentPk,
        LocalDateTime finishedAt,
        String lastError
) {
}
