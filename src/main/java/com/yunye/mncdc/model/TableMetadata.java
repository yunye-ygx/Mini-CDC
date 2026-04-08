package com.yunye.mncdc.model;

import java.util.List;

public record TableMetadata(
        String database,
        String table,
        List<String> columns,
        List<String> primaryKeys
) {
}
