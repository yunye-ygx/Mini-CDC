package com.yunye.mncdc.model;

public record RedisRowVersion(
        String binlogFilename,
        long nextPosition,
        int eventIndex,
        String transactionId
) {
}
