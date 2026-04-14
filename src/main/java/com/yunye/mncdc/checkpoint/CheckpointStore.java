package com.yunye.mncdc.checkpoint;

import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.entity.CheckpointOffsetEntity;
import com.yunye.mncdc.mapper.CheckpointMapper;
import com.yunye.mncdc.model.BinlogCheckpoint;
import com.yunye.mncdc.model.MasterStatusRecord;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CheckpointStore {

    private final MiniCdcProperties properties;
    private final CheckpointMapper checkpointMapper;

    public Optional<BinlogCheckpoint> load() {
        String connectorName = getConnectorName();
        try {
            CheckpointOffsetEntity entity = checkpointMapper.selectById(connectorName);
            if (entity == null
                    || !StringUtils.hasText(entity.getBinlogFilename())
                    || entity.getBinlogPosition() == null) {
                return Optional.empty();
            }
            return Optional.of(new BinlogCheckpoint(
                    entity.getConnectorName(),
                    entity.getBinlogFilename(),
                    entity.getBinlogPosition()
            ));
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to load CDC checkpoint.", exception);
        }
    }

    public void save(BinlogCheckpoint checkpoint) {
        try {
            checkpointMapper.insertOrUpdate(toEntity(checkpoint));
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to persist CDC checkpoint.", exception);
        }
    }

    public BinlogCheckpoint loadLatestServerCheckpoint() {
        try {
            MasterStatusRecord masterStatus = checkpointMapper.selectMasterStatus(); // 获得当前主库的binlog文件名和位置
            if (masterStatus == null) {
                throw new IllegalStateException("SHOW MASTER STATUS returned no rows.");
            }
            return new BinlogCheckpoint(
                    getConnectorName(),
                    masterStatus.file(),
                    masterStatus.position()
            );
        } catch (PersistenceException | DataAccessException exception) {
            throw new IllegalStateException("Failed to query current MySQL binlog position.", exception);
        }
    }

    private String getConnectorName() {
        String connectorName = properties.getCheckpoint().getConnectorName();
        if (!StringUtils.hasText(connectorName)) {
            throw new IllegalStateException("mini-cdc.checkpoint.connector-name must not be blank.");
        }
        return connectorName;
    }

    private CheckpointOffsetEntity toEntity(BinlogCheckpoint checkpoint) {
        CheckpointOffsetEntity entity = new CheckpointOffsetEntity();
        entity.setConnectorName(checkpoint.connectorName());
        entity.setBinlogFilename(checkpoint.binlogFilename());
        entity.setBinlogPosition(checkpoint.binlogPosition());
        return entity;
    }
}
