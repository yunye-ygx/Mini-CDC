package com.yunye.mncdc.model;

public record SchemaState(
        String databaseName,
        String tableName,
        String status,
        String schemaBinlogFile,
        Long schemaNextPosition,
        String ddlType,
        String ddlSql
) {

    public String tableKey() {
        return databaseName + "." + tableName;
    }
}
