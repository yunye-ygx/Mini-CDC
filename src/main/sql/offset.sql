CREATE TABLE cdc_offset (
                            connector_name   VARCHAR(128) NOT NULL COMMENT 'CDC连接器唯一标识；一套监听链路对应一条位点记录',
                            database_name    VARCHAR(64)  NOT NULL COMMENT '当前连接器监听的数据库名；便于校验和排查',
                            TABLE_NAME       VARCHAR(64)  NOT NULL COMMENT '当前连接器监听的表名；便于校验和排查',
                            binlog_filename  VARCHAR(255) DEFAULT NULL COMMENT '当前已提交checkpoint对应的binlog文件名，例如 mysql-bin.000003',
                            binlog_position  BIGINT UNSIGNED DEFAULT NULL COMMENT '当前已提交checkpoint对应的下一个事件位置',
                            gtid_set         TEXT DEFAULT NULL COMMENT 'GTID预留字段；后续若切换为GTID恢复，可直接复用',
                            VERSION          BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号；为以后多实例或并发更新位点预留',
                            updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近一次成功推进checkpoint的时间',
                            PRIMARY KEY (connector_name)
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COMMENT='CDC位点持久化表';