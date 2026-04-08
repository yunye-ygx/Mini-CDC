package com.yunye.mncdc.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CdcEventMessage {

    private String database;

    private String table;

    private String eventType;

    private Map<String, Object> primaryKey;

    private Map<String, Object> before;

    private Map<String, Object> after;

    private long timestamp;
}
