package com.yunye.mncdc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.model.CdcEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.StringJoiner;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mini-cdc", name = "enabled", havingValue = "true")
public class RedisSyncConsumer {

    private final ObjectMapper objectMapper;

    private final StringRedisTemplate stringRedisTemplate;

    private final MiniCdcProperties properties;

    @KafkaListener(topics = "${mini-cdc.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String payload) {
        try {
            CdcEventMessage message = objectMapper.readValue(payload, CdcEventMessage.class);
            if (message.getAfter() == null || message.getPrimaryKey() == null || message.getPrimaryKey().isEmpty()) {
                return;
            }
            String redisKey = properties.getRedis().getKeyPrefix() + buildPrimaryKeySuffix(message.getPrimaryKey());
            String redisValue = objectMapper.writeValueAsString(message.getAfter());
            stringRedisTemplate.opsForValue().set(redisKey, redisValue);
        } catch (JsonProcessingException exception) {
            log.error("Failed to parse CDC message from Kafka.", exception);
        }
    }

    private String buildPrimaryKeySuffix(Map<String, Object> primaryKey) {
        StringJoiner joiner = new StringJoiner(":");
        primaryKey.values().forEach(value -> joiner.add(String.valueOf(value)));
        return joiner.toString();
    }
}
