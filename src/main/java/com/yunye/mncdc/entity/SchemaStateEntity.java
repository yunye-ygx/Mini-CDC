package com.yunye.mncdc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("schema_state")
public class SchemaStateEntity {

    @TableId(value = "table_key", type = IdType.INPUT)
    private String tableKey;

    @TableField("database_name")
    private String databaseName;

    @TableField("table_name")
    private String tableName;

    @TableField("status")
    private String status;

    @TableField("schema_binlog_file")
    private String schemaBinlogFile;

    @TableField("schema_next_position")
    private Long schemaNextPosition;

    @TableField("ddl_type")
    private String ddlType;

    @TableField("ddl_sql")
    private String ddlSql;
}
