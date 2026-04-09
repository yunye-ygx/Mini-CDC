package com.yunye.mncdc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yunye.mncdc.model.MasterStatusRecord;
import com.yunye.mncdc.entity.CheckpointOffsetEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CheckpointMapper extends BaseMapper<CheckpointOffsetEntity> {

    @Select("SHOW MASTER STATUS")
    @Results({
            @Result(column = "File", property = "file"),
            @Result(column = "Position", property = "position")
    })
    MasterStatusRecord selectMasterStatus();
}
