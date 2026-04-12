package com.yunye.mncdc.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.exception.CdcMessageParseException;
import com.yunye.mncdc.model.CdcTransactionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mini-cdc", name = "enabled", havingValue = "true")
public class RedisSyncConsumer {

    private final ObjectMapper objectMapper;

    private final RedisTransactionApplier redisTransactionApplier;

    private final MiniCdcProperties properties;

    @KafkaListener(topics = "${mini-cdc.kafka.topic}")
    public void consume(ConsumerRecord<String, String> record,
                        @Header(value = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt,
                        Acknowledgment acknowledgment) {
        CdcTransactionEvent transactionEvent = deserialize(record.value());

        if (transactionEvent.events() == null || transactionEvent.events().isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }

        try {
            RedisTransactionApplier.ApplyResult result = redisTransactionApplier.apply(transactionEvent);
            if (result == null) {
                throw new IllegalStateException("Unexpected Redis apply result: null for transaction " + transactionEvent.transactionId());
            }
            if (result != RedisTransactionApplier.ApplyResult.APPLIED
                    && result != RedisTransactionApplier.ApplyResult.DUPLICATE) {
                throw new IllegalStateException("Unexpected Redis apply result: " + result + " for transaction " + transactionEvent.transactionId());
            }
            log.info("Redis sync consumer {} transaction {}", result, transactionEvent.transactionId());
            acknowledgment.acknowledge();
        } catch (RuntimeException exception) {
            int attempt = deliveryAttempt == null ? 1 : deliveryAttempt;
            int maxAttempts = properties.getKafka().getConsumer().getMaxAttempts();
            log.error(
                    "Redis sync failed transactionId={} topic={} partition={} offset={} attempt={}/{}",
                    transactionEvent.transactionId(),
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    attempt,
                    maxAttempts,
                    exception
            );
            throw exception;
        }
    }

    private CdcTransactionEvent deserialize(String payload) {
        if (payload == null) {
            throw new CdcMessageParseException("Failed to parse CDC transaction message from Kafka: payload is null.", null);
        }
        if ("null".equals(payload.trim())) {
            throw new CdcMessageParseException("Failed to parse CDC transaction message from Kafka: payload is JSON null.", null);
        }
        try {
            CdcTransactionEvent transactionEvent = objectMapper.readValue(payload, CdcTransactionEvent.class);
            if (transactionEvent == null) {
                throw new CdcMessageParseException("Failed to parse CDC transaction message from Kafka: deserialized message is null.", null);
            }
            return transactionEvent;
        } catch (JsonProcessingException exception) {
            throw new CdcMessageParseException("Failed to parse CDC transaction message from Kafka.", exception);
        }
    }
}
