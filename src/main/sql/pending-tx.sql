CREATE TABLE IF NOT EXISTS pending_tx (
    transaction_id              VARCHAR(255) NOT NULL COMMENT '被暂存的 Kafka 事务标识',
    connector_name              VARCHAR(128) NOT NULL COMMENT 'CDC 连接器名称',
    binlog_filename             VARCHAR(255) NOT NULL COMMENT '原始事务对应的 binlog 文件名',
    next_position               BIGINT UNSIGNED NOT NULL COMMENT '原始事务对应的 next position',
    payload_json                LONGTEXT NOT NULL COMMENT '后续回放所需的 Kafka envelope 序列化内容',
    blocked_tables              JSON NOT NULL COMMENT '导致该事务被暂存的表标识 JSON 数组',
    blocked_schema_binlog_file  VARCHAR(255) NOT NULL COMMENT '阻塞该事务的 schema binlog 文件名',
    blocked_schema_next_position BIGINT UNSIGNED NOT NULL COMMENT '阻塞该事务的 schema next position',
    status                      VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '回放状态：PENDING / REPLAYED',
    created_at                  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '暂存记录创建时间',
    updated_at                  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '暂存记录最近一次更新时间',
    PRIMARY KEY (transaction_id),
    KEY idx_pending_tx_status (status, binlog_filename, next_position),
    KEY idx_pending_tx_blocked_schema (blocked_schema_binlog_file, blocked_schema_next_position)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='等待表重建完成后再回放的暂存事务表';
