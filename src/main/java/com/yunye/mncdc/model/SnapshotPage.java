package com.yunye.mncdc.model;

import java.util.List;
import java.util.Map;

public record SnapshotPage(
        List<Map<String, Object>> rows,
        Map<String, Object> lastPrimaryKey,
        boolean hasMore
) {
    public SnapshotPage {
        rows = List.copyOf(rows.stream()
                .map(Map::copyOf)
                .toList());
        lastPrimaryKey = Map.copyOf(lastPrimaryKey);
    }
}
