package com.yunye.mncdc.model;

public record RedisRowMetadata(
        boolean deleted,
        RedisRowVersion version
) {
}
