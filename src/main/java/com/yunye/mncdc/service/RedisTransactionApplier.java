package com.yunye.mncdc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.model.CdcTransactionEvent;
import com.yunye.mncdc.model.CdcTransactionRow;
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
public class RedisTransactionApplier {

    private static final DefaultRedisScript<String> APPLY_TRANSACTION_SCRIPT = createApplyTransactionScript();

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    private final MiniCdcProperties properties;

    private static DefaultRedisScript<String> createApplyTransactionScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/apply-transaction.lua"));
        script.setResultType(String.class);
        return script;
    }

    public ApplyResult apply(CdcTransactionEvent transactionEvent) {
        List<String> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        keys.add(buildDoneKey(transactionEvent.transactionId()));
        for (CdcTransactionRow event : transactionEvent.events()) {
            if (event.primaryKey() == null || event.primaryKey().isEmpty() || event.after() == null) {
                throw new IllegalStateException("CDC transaction row must contain primaryKey and after.");
            }
            keys.add(buildBusinessKey(event.primaryKey()));
            values.add(toJson(event.after()));
        }
        values.add("1");
        String result = stringRedisTemplate.execute(APPLY_TRANSACTION_SCRIPT, keys, values.toArray());
        return ApplyResult.from(result);
    }

    private String toJson(Map<String, Object> row) {
        try {
            return objectMapper.writeValueAsString(row);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize Redis row payload.", exception);
        }
    }

    private String buildDoneKey(String transactionId) {
        return properties.getRedis().getTransactionDonePrefix() + transactionId;
    }

    private String buildBusinessKey(Map<String, Object> primaryKey) {
        return properties.getRedis().getKeyPrefix() + buildPrimaryKeySuffix(primaryKey);
    }

    private String buildPrimaryKeySuffix(Map<String, Object> primaryKey) {
        StringJoiner joiner = new StringJoiner(":");
        primaryKey.values().forEach(value -> joiner.add(String.valueOf(value)));
        return joiner.toString();
    }

    public enum ApplyResult {
        APPLIED,
        DUPLICATE;

        public static ApplyResult from(String value) {
            if ("APPLIED".equals(value)) {
                return APPLIED;
            }
            if ("DUPLICATE".equals(value)) {
                return DUPLICATE;
            }
            throw new IllegalStateException("Unexpected Redis apply result: " + value);
        }
    }
}
