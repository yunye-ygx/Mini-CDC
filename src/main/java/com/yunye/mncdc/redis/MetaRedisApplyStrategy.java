package com.yunye.mncdc.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.model.CdcTransactionEvent;
import com.yunye.mncdc.model.CdcTransactionRow;
import com.yunye.mncdc.model.RedisRowMetadata;
import com.yunye.mncdc.model.RedisRowVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Service
@RequiredArgsConstructor
public class MetaRedisApplyStrategy implements RedisApplyStrategy {

    private static final DefaultRedisScript<String> APPLY_TRANSACTION_SCRIPT = createApplyTransactionScript();

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    private final MiniCdcProperties properties;

    private static DefaultRedisScript<String> createApplyTransactionScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/apply-transaction-meta.lua"));
        script.setResultType(String.class);
        return script;
    }

    @Override
    public RedisTransactionApplier.ApplyResult apply(CdcTransactionEvent transactionEvent) {
        List<String> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        keys.add(properties.getRedis().getTransactionDonePrefix() + transactionEvent.transactionId());
        for (CdcTransactionRow row : transactionEvent.events()) {
            if (row.primaryKey() == null || row.primaryKey().isEmpty()) {
                throw new IllegalStateException("CDC transaction row must contain primaryKey.");
            }
            String eventType = row.eventType();
            boolean delete = "DELETE".equals(eventType);
            if (!delete && row.after() == null) {
                throw new IllegalStateException("CDC transaction row must contain after for INSERT/UPDATE/SNAPSHOT_UPSERT.");
            }

            String qualifiedTable = buildQualifiedRowIdentity(row);
            String primaryKeySuffix = buildPrimaryKeySuffix(row.primaryKey());
            keys.add(properties.getRedis().getKeyPrefix() + qualifiedTable + ":" + primaryKeySuffix);
            keys.add(properties.getRedis().getRowMetaPrefix() + qualifiedTable + ":" + primaryKeySuffix);

            RedisRowMetadata metadata = new RedisRowMetadata(
                    delete,
                    new RedisRowVersion(
                            transactionEvent.binlogFilename(),
                            transactionEvent.nextPosition(),
                            row.eventIndex(),
                            transactionEvent.transactionId()
                    )
            );

            values.add(resolveRedisEventType(eventType));
            values.add(delete ? "" : toJson(row.after()));
            values.add(toJson(metadata));
        }
        values.add("1");
        String result = stringRedisTemplate.execute(APPLY_TRANSACTION_SCRIPT, keys, values.toArray());
        return RedisTransactionApplier.ApplyResult.from(result);
    }

    private String resolveRedisEventType(String eventType) {
        if ("SNAPSHOT_UPSERT".equals(eventType)) {
            return "UPDATE";
        }
        return eventType;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize Redis metadata payload.", exception);
        }
    }

    private String buildPrimaryKeySuffix(Map<String, Object> primaryKey) {
        StringJoiner joiner = new StringJoiner(":");
        primaryKey.values().forEach(value -> joiner.add(String.valueOf(value)));
        return joiner.toString();
    }

    private String buildQualifiedRowIdentity(CdcTransactionRow row) {
        String table = row.table();
        String database = row.database();
        if (database == null || database.isBlank() || table == null || table.isBlank()) {
            throw new IllegalStateException("CDC transaction row must contain database/table identity.");
        }
        return database + "." + table;
    }
}
