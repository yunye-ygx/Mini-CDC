package com.yunye.mncdc.ddl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.model.CdcMessageEnvelope;
import com.yunye.mncdc.model.PendingTransaction;
import com.yunye.mncdc.model.RebuildTask;
import com.yunye.mncdc.model.SchemaState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisTableRebuildWorker {

    private final ObjectMapper objectMapper;
    private final MiniCdcProperties properties;
    private final SchemaStateStore schemaStateStore;
    private final RebuildTaskStore rebuildTaskStore;
    private final PendingTransactionStore pendingTransactionStore;
    private final TransactionRoutingService transactionRoutingService;
    private final RedisStagingRebuildService stagingRebuildService;

    public void runTask(RebuildTask task) {
        try {
            schemaStateStore.markRebuilding(
                    task.databaseName(),
                    task.tableName(),
                    task.schemaBinlogFile(),
                    task.schemaNextPosition()
            );



            stagingRebuildService.rebuildToStaging(task);
            SchemaState current = schemaStateStore.load(task.databaseName(), task.tableName());
            if (!matches(current, task)) {
                stagingRebuildService.discard(task);
                rebuildTaskStore.markObsolete(task.taskId());
                return;
            }
            stagingRebuildService.publish(task);
            replayPendingTransactions(task);
            schemaStateStore.markActive(
                    task.databaseName(),
                    task.tableName(),
                    task.schemaBinlogFile(),
                    task.schemaNextPosition()
            );
            rebuildTaskStore.markDone(task.taskId());
        } catch (Exception exception) {
            discardStagingQuietly(task, exception);
            handleFailure(task, exception);
        }
    }

    private void replayPendingTransactions(RebuildTask task) {
        List<PendingTransaction> replayCandidates = pendingTransactionStore.loadReplayCandidates(
                task.databaseName(),
                task.tableName(),
                task.schemaBinlogFile(),
                task.schemaNextPosition()
        );
        for (PendingTransaction pending : replayCandidates) {
            try {
                CdcMessageEnvelope envelope = objectMapper.readValue(pending.payloadJson(), CdcMessageEnvelope.class);
                transactionRoutingService.replayBuffered(envelope.transaction());
                pendingTransactionStore.markReplayed(pending.transactionId());
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Failed to deserialize pending transaction " + pending.transactionId(), exception);
            }
        }
    }

    private boolean matches(SchemaState current, RebuildTask task) {
        return current != null
                && task.schemaBinlogFile().equals(current.schemaBinlogFile())
                && task.schemaNextPosition().equals(current.schemaNextPosition());
    }

    private void handleFailure(RebuildTask task, Exception exception) {
        String errorMessage = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        int currentRetryCount = task.retryCount() == null ? 0 : task.retryCount();
        int nextRetryCount = currentRetryCount + 1;
        if (nextRetryCount >= properties.getDdl().getMaxRetries()) {
            schemaStateStore.markRebuildFailed(
                    task.databaseName(),
                    task.tableName(),
                    task.schemaBinlogFile(),
                    task.schemaNextPosition()
            );
            rebuildTaskStore.markFailed(task.taskId(), errorMessage);
            return;
        }
        schemaStateStore.markRebuildRequired(
                task.databaseName(),
                task.tableName(),
                task.schemaBinlogFile(),
                task.schemaNextPosition()
        );
        rebuildTaskStore.requeue(task.taskId(), errorMessage);
    }

    private void discardStagingQuietly(RebuildTask task, Exception originalException) {
        try {
            stagingRebuildService.discard(task);
        } catch (RuntimeException discardException) {
            log.warn("Failed to discard staging data for task {}", task.taskId(), discardException);
            originalException.addSuppressed(discardException);
        }
    }
}
