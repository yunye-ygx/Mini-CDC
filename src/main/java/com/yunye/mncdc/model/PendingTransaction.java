package com.yunye.mncdc.model;

public record PendingTransaction(
        String transactionId,
        String connectorName,
        String binlogFilename,
        Long nextPosition,
        String payloadJson,
        String blockedTables,
        String blockedSchemaBinlogFile,
        Long blockedSchemaNextPosition,
        String status
) {
}
