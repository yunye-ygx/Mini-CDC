CREATE TABLE IF NOT EXISTS full_sync_task (
    connector_name           VARCHAR(128) NOT NULL COMMENT 'CDC connector 标识；同一个 connector 下可对应多张监听表',
    database_name            VARCHAR(64)  NOT NULL COMMENT '全量同步对应的数据库名',
    table_name               VARCHAR(64)  NOT NULL COMMENT '全量同步对应的表名',
    status                   VARCHAR(32)  NOT NULL COMMENT '该表的全量任务状态：RUNNING / COMPLETED / FAILED',
    cutover_binlog_filename  VARCHAR(255) NOT NULL COMMENT '本次 connector 级全量开始前记录的 binlog 文件名；同一批次各表通常相同',
    cutover_binlog_position  BIGINT UNSIGNED NOT NULL COMMENT '本次 connector 级全量开始前记录的 binlog 位点；同一批次各表通常相同',
    last_sent_pk             JSON DEFAULT NULL COMMENT '该表最近一个已成功发送到 Kafka 的主键游标；单主键示例 {\"id\":123}，联合主键示例 {\"tenant_id\":\"t1\",\"user_id\":\"u9\"}',
    started_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该表全量任务开始时间',
    finished_at              DATETIME DEFAULT NULL COMMENT '该表全量任务完成或失败时间',
    last_error               VARCHAR(1000) DEFAULT NULL COMMENT '该表最近一次失败原因',
    updated_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (connector_name, database_name, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全量同步任务状态表；一个 connector 下每张监听表一条记录';

SET @full_sync_task_primary_key = (
    SELECT COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ','), '')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'full_sync_task'
      AND index_name = 'PRIMARY'
);

SET @full_sync_task_pk_sql = IF(
    @full_sync_task_primary_key = 'connector_name,database_name,table_name',
    'SELECT 1',
    'ALTER TABLE full_sync_task DROP PRIMARY KEY, ADD PRIMARY KEY (connector_name, database_name, table_name)'
);

PREPARE full_sync_task_pk_stmt FROM @full_sync_task_pk_sql;
EXECUTE full_sync_task_pk_stmt;
DEALLOCATE PREPARE full_sync_task_pk_stmt;
