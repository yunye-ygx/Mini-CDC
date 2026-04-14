CREATE TABLE cdc_offset (
    connector_name  VARCHAR(128) NOT NULL,
    binlog_filename VARCHAR(255) DEFAULT NULL,
    binlog_position BIGINT UNSIGNED DEFAULT NULL,
    version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (connector_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Connector-level CDC checkpoint table';
