package com.yunye.mncdc.model;

public record CdcMessageEnvelope(
        String messageType,
        CdcTransactionEvent transaction,
        CdcSchemaChangeEvent schemaChange
) {

    public static CdcMessageEnvelope transaction(CdcTransactionEvent event) {
        return new CdcMessageEnvelope("TRANSACTION", event, null);
    }

    public static CdcMessageEnvelope schemaChange(CdcSchemaChangeEvent event) {
        return new CdcMessageEnvelope("SCHEMA_CHANGE", null, event);
    }
}
