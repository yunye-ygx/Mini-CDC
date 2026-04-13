CREATE TABLE full_sync_task (
    connector_name           VARCHAR(128) NOT NULL COMMENT '全量同步任务唯一标识，通常与 CDC connector_name 一致',
    database_name            VARCHAR(64)  NOT NULL COMMENT '全量同步对应的数据库名',
    table_name               VARCHAR(64)  NOT NULL COMMENT '全量同步对应的表名',
    status                   VARCHAR(32)  NOT NULL COMMENT '任务状态：RUNNING / COMPLETED / FAILED',
    cutover_binlog_filename  VARCHAR(255) NOT NULL COMMENT '全量开始前记录的 binlog 文件名，增量从这里衔接',
    cutover_binlog_position  BIGINT UNSIGNED NOT NULL COMMENT '全量开始前记录的 binlog 位点，增量从这里衔接',
    last_sent_pk             JSON DEFAULT NULL COMMENT '最近一个已成功发送到 Kafka 的主键游标；单主键示例 {\"id\":123}，联合主键示例 {\"tenant_id\":\"t1\",\"user_id\":\"u9\"}',
    started_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '全量任务开始时间',
    finished_at              DATETIME DEFAULT NULL COMMENT '全量任务完成或失败时间',
    last_error               VARCHAR(1000) DEFAULT NULL COMMENT '最近一次失败原因',
    updated_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (connector_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全量同步任务状态表';
