package com.yunye.mncdc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yunye.mncdc.model.MasterStatusRecord;
import com.yunye.mncdc.entity.CheckpointOffsetEntity;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CheckpointMapper extends BaseMapper<CheckpointOffsetEntity> {

    @Select("SHOW MASTER STATUS")
    @ConstructorArgs({
            @Arg(column = "File", javaType = String.class),
            @Arg(column = "Position", javaType = long.class)
    })
    MasterStatusRecord selectMasterStatus();
}
