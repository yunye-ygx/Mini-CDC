CREATE TABLE IF NOT EXISTS schema_state (
    table_key               VARCHAR(255) NOT NULL COMMENT '稳定的表标识，格式为 database_name.table_name',
    database_name           VARCHAR(128) NOT NULL COMMENT '监听表所属的数据库名',
    table_name              VARCHAR(128) NOT NULL COMMENT '监听的表名',
    status                  VARCHAR(32)  NOT NULL COMMENT '表重建状态：ACTIVE / REBUILD_REQUIRED / REBUILDING',
    schema_binlog_file      VARCHAR(255) NOT NULL COMMENT '该表最近一次已接收 DDL 对应的 binlog 文件名',
    schema_next_position    BIGINT UNSIGNED NOT NULL COMMENT '该表最近一次已接收 DDL 对应的 next position',
    ddl_type                VARCHAR(64)  NOT NULL COMMENT '最近一次已接收的 DDL 类型，例如 DROP_COLUMN 或 MODIFY_COLUMN',
    ddl_sql                 TEXT NOT NULL COMMENT '最近一次已接收的 DDL 原始 SQL',
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '该行 schema 状态最近一次更新时间',
    PRIMARY KEY (table_key),
    UNIQUE KEY uk_schema_state_table (database_name, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='下游按表维护的 schema 控制状态表';
