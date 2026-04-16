package com.yunye.mncdc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("rebuild_task")
public class RebuildTaskEntity {

    @TableId(value = "task_id", type = IdType.INPUT)
    private String taskId;

    @TableField("database_name")
    private String databaseName;

    @TableField("table_name")
    private String tableName;

    @TableField("schema_binlog_file")
    private String schemaBinlogFile;

    @TableField("schema_next_position")
    private Long schemaNextPosition;

    @TableField("status")
    private String status;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("last_error")
    private String lastError;
}
