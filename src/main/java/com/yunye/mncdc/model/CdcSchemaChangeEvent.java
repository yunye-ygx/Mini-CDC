package com.yunye.mncdc.model;

public record CdcSchemaChangeEvent(
        String eventId,
        String connectorName,
        String database,
        String table,
        String ddlType,
        String rawSql,
        String binlogFilename,
        long nextPosition,
        long timestamp
) {
}
