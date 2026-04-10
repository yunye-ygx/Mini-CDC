package com.yunye.mncdc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.model.CdcEventMessage;
import com.yunye.mncdc.model.CdcTransactionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.StringJoiner;

@Slf4j
@Service
@RequiredArgsConstructor
public class CdcEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper;

    private final MiniCdcProperties properties;

    public CompletableFuture<SendResult<String, String>> publish(CdcEventMessage message) {
        return publishPayload(buildKey(message.getPrimaryKey()), message);
    }

    public CompletableFuture<SendResult<String, String>> publishTransaction(CdcTransactionEvent transactionEvent) {
        return publishPayload(transactionEvent.transactionId(), transactionEvent);
    }

    private CompletableFuture<SendResult<String, String>> publishPayload(String key, Object payloadObject) {
        try {
            String payload = objectMapper.writeValueAsString(payloadObject);
            if (properties.isLogEventJson()) {
                log.info("{}", payload);
            }
            return kafkaTemplate.send(properties.getKafka().getTopic(), key, payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize CDC event.", exception);
        }
    }

    private String buildKey(Map<String, Object> primaryKey) {
        StringJoiner joiner = new StringJoiner(":");
        primaryKey.values().forEach(value -> joiner.add(String.valueOf(value)));
        return joiner.toString();
    }
}
