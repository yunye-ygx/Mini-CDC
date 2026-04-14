package com.yunye.mncdc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("cdc_offset")
public class CheckpointOffsetEntity {

    @TableId(value = "connector_name", type = IdType.INPUT)
    private String connectorName;

    @TableField("binlog_filename")
    private String binlogFilename;

    @TableField("binlog_position")
    private Long binlogPosition;
}
