package com.yunye.mncdc.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SnapshotPage(
        List<Map<String, Object>> rows,
        Map<String, Object> lastPrimaryKey,
        boolean hasMore
) {
    public SnapshotPage {
        rows = List.copyOf(rows.stream()
                .map(SnapshotPage::copyRow)
                .toList());
        lastPrimaryKey = Map.copyOf(lastPrimaryKey);
    }

    private static Map<String, Object> copyRow(Map<String, Object> row) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(row));
    }
}
