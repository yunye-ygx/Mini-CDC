package com.yunye.mncdc.model;

public record RebuildTask(
        String taskId,
        String databaseName,
        String tableName,
        String schemaBinlogFile,
        Long schemaNextPosition,
        String status,
        Integer retryCount,
        String lastError
) {
}
