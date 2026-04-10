package com.yunye.mncdc.model;

public record BinlogCheckpoint(
        String connectorName,
        String databaseName,
        String tableName,
        String binlogFilename,
        long binlogPosition
) {
}
