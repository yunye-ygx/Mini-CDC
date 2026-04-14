package com.yunye.mncdc.model;

public record BinlogCheckpoint(
        String connectorName,
        String binlogFilename,
        long binlogPosition
) {
}
