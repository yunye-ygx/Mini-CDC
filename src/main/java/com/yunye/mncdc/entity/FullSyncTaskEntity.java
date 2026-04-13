package com.yunye.mncdc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("full_sync_task")
public class FullSyncTaskEntity {

    @TableId(value = "connector_name", type = IdType.INPUT)
    private String connectorName;

    @TableField("database_name")
    private String databaseName;

    @TableField("table_name")
    private String tableName;

    @TableField("status")
    private String status;

    @TableField("cutover_binlog_filename")
    private String cutoverBinlogFilename;

    @TableField("cutover_binlog_position")
    private Long cutoverBinlogPosition;

    @TableField("last_sent_pk")
    private String lastSentPk;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;

    @TableField("last_error")
    private String lastError;
}
