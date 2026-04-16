package com.yunye.mncdc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("pending_tx")
public class PendingTransactionEntity {

    @TableId(value = "transaction_id", type = IdType.INPUT)
    private String transactionId;

    @TableField("connector_name")
    private String connectorName;

    @TableField("binlog_filename")
    private String binlogFilename;

    @TableField("next_position")
    private Long nextPosition;

    @TableField("payload_json")
    private String payloadJson;

    @TableField("blocked_tables")
    private String blockedTables;

    @TableField("blocked_schema_binlog_file")
    private String blockedSchemaBinlogFile;

    @TableField("blocked_schema_next_position")
    private Long blockedSchemaNextPosition;

    @TableField("status")
    private String status;
}
