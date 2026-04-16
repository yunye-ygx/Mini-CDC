package com.yunye.mncdc.ddl;

import com.yunye.mncdc.entity.PendingTransactionEntity;
import com.yunye.mncdc.mapper.PendingTransactionMapper;
import com.yunye.mncdc.model.PendingTransaction;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PendingTransactionStore {

    private final PendingTransactionMapper pendingTransactionMapper;

    public void save(PendingTransaction pendingTransaction) {
        try {
            pendingTransactionMapper.insert(toEntity(pendingTransaction));
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist pending transaction.", exception);
        }
    }

    public List<PendingTransaction> loadReplayCandidates(
            String databaseName,
            String tableName,
            String blockedSchemaBinlogFile,
            Long blockedSchemaNextPosition
    ) {
        try {
            return pendingTransactionMapper.selectReplayCandidates(
                    databaseName + "." + tableName,
                    blockedSchemaBinlogFile,
                    blockedSchemaNextPosition
            ).stream().map(this::toModel).toList();
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to load pending transactions.", exception);
        }
    }

    public void markReplayed(String transactionId) {
        try {
            pendingTransactionMapper.markReplayed(transactionId);
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist pending transaction.", exception);
        }
    }

    private PendingTransactionEntity toEntity(PendingTransaction pendingTransaction) {
        PendingTransactionEntity entity = new PendingTransactionEntity();
        entity.setTransactionId(pendingTransaction.transactionId());
        entity.setConnectorName(pendingTransaction.connectorName());
        entity.setBinlogFilename(pendingTransaction.binlogFilename());
        entity.setNextPosition(pendingTransaction.nextPosition());
        entity.setPayloadJson(pendingTransaction.payloadJson());
        entity.setBlockedTables(pendingTransaction.blockedTables());
        entity.setBlockedSchemaBinlogFile(pendingTransaction.blockedSchemaBinlogFile());
        entity.setBlockedSchemaNextPosition(pendingTransaction.blockedSchemaNextPosition());
        entity.setStatus(pendingTransaction.status());
        return entity;
    }

    private PendingTransaction toModel(PendingTransactionEntity entity) {
        return new PendingTransaction(
                entity.getTransactionId(),
                entity.getConnectorName(),
                entity.getBinlogFilename(),
                entity.getNextPosition(),
                entity.getPayloadJson(),
                entity.getBlockedTables(),
                entity.getBlockedSchemaBinlogFile(),
                entity.getBlockedSchemaNextPosition(),
                entity.getStatus()
        );
    }
}
