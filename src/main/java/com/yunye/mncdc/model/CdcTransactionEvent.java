package com.yunye.mncdc.model;

import java.util.List;

public record CdcTransactionEvent(
        String transactionId,
        String connectorName,
        String binlogFilename,
        long xid,
        long nextPosition,
        long timestamp,
        List<CdcTransactionRow> events
) {
}
