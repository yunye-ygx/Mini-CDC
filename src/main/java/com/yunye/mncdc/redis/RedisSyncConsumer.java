package com.yunye.mncdc.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.ddl.SchemaChangeMessageHandler;
import com.yunye.mncdc.ddl.TransactionRoutingService;
import com.yunye.mncdc.exception.CdcMessageParseException;
import com.yunye.mncdc.model.CdcMessageEnvelope;
import com.yunye.mncdc.model.CdcTransactionRow;
import com.yunye.mncdc.ops.CdcObservabilityService;
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

    private final SchemaChangeMessageHandler schemaChangeMessageHandler;

    private final TransactionRoutingService transactionRoutingService;

    private final MiniCdcProperties properties;

    private final CdcObservabilityService observabilityService;

    @KafkaListener(topics = "${mini-cdc.kafka.topic}")
    public void consume(ConsumerRecord<String, String> record,
                        @Header(value = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt,
                        Acknowledgment acknowledgment) {
        CdcMessageEnvelope envelope = deserialize(record.value());
        if ("SCHEMA_CHANGE".equals(envelope.messageType())) {
            schemaChangeMessageHandler.handle(envelope.schemaChange());
            acknowledgment.acknowledge();
            return;
        }
        if (envelope.transaction() == null || envelope.transaction().events() == null || envelope.transaction().events().isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }

        try {
            TransactionRoutingService.RouteResult result = transactionRoutingService.route(envelope); //判断力此次事件是否是涉及DDL事件的表，如果是就阻塞，如果不是就插入
            if (result == null) {
                throw new IllegalStateException("Unexpected Redis apply result: null for transaction " + envelope.transaction().transactionId());
            }
            CdcTransactionRow first = envelope.transaction().events().get(0);
            if (result == TransactionRoutingService.RouteResult.APPLIED) {
                observabilityService.recordTransactionApplied(
                        first.database(),
                        first.table(),
                        envelope.transaction().transactionId()
                );
            }
            if (result == TransactionRoutingService.RouteResult.BUFFERED) {
                observabilityService.recordTransactionBuffered(
                        first.database(),
                        first.table(),
                        envelope.transaction().transactionId()
                );
            }
            log.info("Redis sync consumer {} transaction {}", result, envelope.transaction().transactionId());
            acknowledgment.acknowledge();
        } catch (RuntimeException exception) {
            if (envelope.transaction() != null && envelope.transaction().events() != null && !envelope.transaction().events().isEmpty()) {
                CdcTransactionRow first = envelope.transaction().events().get(0);
                observabilityService.recordTransactionFailed(
                        first.database(),
                        first.table(),
                        envelope.transaction().transactionId(),
                        exception.getMessage()
                );
            }
            int attempt = deliveryAttempt == null ? 1 : deliveryAttempt;
            int maxAttempts = properties.getKafka().getConsumer().getMaxAttempts();
            log.error(
                    "Redis sync failed transactionId={} topic={} partition={} offset={} attempt={}/{}",
                    envelope.transaction() == null ? null : envelope.transaction().transactionId(),
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

    private CdcMessageEnvelope deserialize(String payload) {
        if (payload == null) {
            throw new CdcMessageParseException("Failed to parse CDC transaction message from Kafka: payload is null.", null);
        }
        if ("null".equals(payload.trim())) {
            throw new CdcMessageParseException("Failed to parse CDC transaction message from Kafka: payload is JSON null.", null);
        }
        try {
            CdcMessageEnvelope envelope = objectMapper.readValue(payload, CdcMessageEnvelope.class);
            if (envelope == null) {
                throw new CdcMessageParseException("Failed to parse CDC transaction message from Kafka: deserialized message is null.", null);
            }
            return envelope;
        } catch (JsonProcessingException exception) {
            throw new CdcMessageParseException("Failed to parse CDC transaction message from Kafka.", exception);
        }
    }
}
