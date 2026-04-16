CREATE TABLE IF NOT EXISTS rebuild_task (
    task_id                 VARCHAR(128) NOT NULL COMMENT '重建任务唯一标识',
    database_name           VARCHAR(128) NOT NULL COMMENT '待重建表所属的数据库名',
    table_name              VARCHAR(128) NOT NULL COMMENT '待重建的表名',
    schema_binlog_file      VARCHAR(255) NOT NULL COMMENT '作为 schema 版本坐标的 DDL binlog 文件名',
    schema_next_position    BIGINT UNSIGNED NOT NULL COMMENT '作为 schema 版本坐标的 DDL next position',
    status                  VARCHAR(32)  NOT NULL COMMENT '任务状态：PENDING / RUNNING / DONE / FAILED / OBSOLETE',
    retry_count             INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '该任务已重试的次数',
    last_error              VARCHAR(1000) DEFAULT NULL COMMENT '最近一次记录的任务错误信息',
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '任务创建时间',
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '任务最近一次更新时间',
    PRIMARY KEY (task_id),
    KEY idx_rebuild_task_table_status (database_name, table_name, status),
    KEY idx_rebuild_task_schema (database_name, table_name, schema_binlog_file, schema_next_position)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='由破坏性 DDL 触发的下游异步表重建任务表';
