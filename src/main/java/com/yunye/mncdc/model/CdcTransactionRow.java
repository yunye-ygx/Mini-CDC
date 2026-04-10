package com.yunye.mncdc.model;

import java.util.Map;

public record CdcTransactionRow(
        String eventType,
        Map<String, Object> primaryKey,
        Map<String, Object> after
) {
}
