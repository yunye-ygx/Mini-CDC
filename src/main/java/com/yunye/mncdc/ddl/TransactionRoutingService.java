package com.yunye.mncdc.ddl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunye.mncdc.model.CdcMessageEnvelope;
import com.yunye.mncdc.model.CdcTransactionEvent;
import com.yunye.mncdc.model.CdcTransactionRow;
import com.yunye.mncdc.model.PendingTransaction;
import com.yunye.mncdc.redis.RedisTransactionApplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TransactionRoutingService {

    private final ObjectMapper objectMapper;
    private final SchemaStateStore schemaStateStore;
    private final PendingTransactionStore pendingTransactionStore;
    private final RedisTransactionApplier redisTransactionApplier;

    public RouteResult route(CdcMessageEnvelope envelope) {
        CdcTransactionEvent transaction = requireTransaction(envelope);
        Set<String> tableKeys = extractTableKeys(transaction);
        if (schemaStateStore.anyNonActive(tableKeys)) {
            SchemaStateStore.SchemaVersion blockingVersion = schemaStateStore.currentBlockingVersion(tableKeys);
            if (blockingVersion == null) {
                throw new IllegalStateException("Blocking schema version is missing for buffered transaction " + transaction.transactionId());
            }
            pendingTransactionStore.save(new PendingTransaction(
                    transaction.transactionId(),
                    transaction.connectorName(),
                    transaction.binlogFilename(),
                    transaction.nextPosition(),
                    serializeEnvelope(envelope),
                    serializeBlockedTables(tableKeys),
                    blockingVersion.binlogFilename(),
                    blockingVersion.nextPosition(),
                    "PENDING"
            ));
            return RouteResult.BUFFERED;
        }
        RedisTransactionApplier.ApplyResult applyResult = redisTransactionApplier.apply(transaction);
        return applyResult == RedisTransactionApplier.ApplyResult.DUPLICATE
                ? RouteResult.DUPLICATE
                : RouteResult.APPLIED;
    }

    public RedisTransactionApplier.ApplyResult replayBuffered(CdcTransactionEvent transaction) {
        return redisTransactionApplier.apply(transaction);
    }

    private CdcTransactionEvent requireTransaction(CdcMessageEnvelope envelope) {
        if (envelope == null || envelope.transaction() == null) {
            throw new IllegalStateException("CDC message envelope does not contain a transaction payload.");
        }
        return envelope.transaction();
    }

    private Set<String> extractTableKeys(CdcTransactionEvent transaction) {
        LinkedHashSet<String> tableKeys = new LinkedHashSet<>();
        if (transaction.events() == null) {
            return tableKeys;
        }
        for (CdcTransactionRow row : transaction.events()) {
            tableKeys.add(row.database() + "." + row.table());
        }
        return tableKeys;
    }

    private String serializeEnvelope(CdcMessageEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize pending CDC envelope.", exception);
        }
    }

    private String serializeBlockedTables(Set<String> tableKeys) {
        try {
            return objectMapper.writeValueAsString(tableKeys);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize blocked table keys.", exception);
        }
    }

    public enum RouteResult {
        APPLIED,
        DUPLICATE,
        BUFFERED
    }
}
