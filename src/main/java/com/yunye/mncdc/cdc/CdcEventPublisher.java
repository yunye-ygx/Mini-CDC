package com.yunye.mncdc.cdc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.model.CdcMessageEnvelope;
import com.yunye.mncdc.model.CdcSchemaChangeEvent;
import com.yunye.mncdc.model.CdcTransactionEvent;
import com.yunye.mncdc.model.CdcTransactionRow;
import com.yunye.mncdc.ops.CdcObservabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class CdcEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper;

    private final MiniCdcProperties properties;

    private final CdcObservabilityService observabilityService;

    public CompletableFuture<SendResult<String, String>> publishTransaction(CdcTransactionEvent transactionEvent) {
        CompletableFuture<SendResult<String, String>> future =
                publishPayload(transactionEvent.transactionId(), CdcMessageEnvelope.transaction(transactionEvent));
        CdcTransactionRow first = firstEvent(transactionEvent);
        if (first != null) {
            observabilityService.recordTransactionPublished(first.database(), first.table(), transactionEvent.transactionId());
        }
        return future;
    }

    public CompletableFuture<SendResult<String, String>> publishSnapshotPage(CdcTransactionEvent snapshotPageEvent) {
        CompletableFuture<SendResult<String, String>> future =
                publishPayload(snapshotPageEvent.transactionId(), CdcMessageEnvelope.transaction(snapshotPageEvent));
        CdcTransactionRow first = firstEvent(snapshotPageEvent);
        if (first != null) {
            observabilityService.recordSnapshotPublished(
                    first.database(),
                    first.table(),
                    snapshotPageEvent.transactionId(),
                    snapshotPageEvent.events().size()
            );
        }
        return future;
    }

    public CompletableFuture<SendResult<String, String>> publishSchemaChange(CdcSchemaChangeEvent schemaChangeEvent) {
        return publishPayload(schemaChangeEvent.eventId(), CdcMessageEnvelope.schemaChange(schemaChangeEvent));
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

    private CdcTransactionRow firstEvent(CdcTransactionEvent transactionEvent) {
        if (transactionEvent == null || transactionEvent.events() == null || transactionEvent.events().isEmpty()) {
            return null;
        }
        return transactionEvent.events().get(0);
    }
}
