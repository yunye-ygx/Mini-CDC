package com.yunye.mncdc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yunye.mncdc.entity.FullSyncTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FullSyncTaskMapper extends BaseMapper<FullSyncTaskEntity> {

    @Update("""
            UPDATE full_sync_task
            SET last_sent_pk = #{lastSentPk}
            WHERE connector_name = #{connectorName}
            """)
    int updateLastSentPk(@Param("connectorName") String connectorName, @Param("lastSentPk") String lastSentPk);

    @Update("""
            UPDATE full_sync_task
            SET status = 'COMPLETED',
                finished_at = CURRENT_TIMESTAMP,
                last_error = NULL
            WHERE connector_name = #{connectorName}
            """)
    int markCompleted(@Param("connectorName") String connectorName);

    @Update("""
            UPDATE full_sync_task
            SET status = 'FAILED',
                finished_at = CURRENT_TIMESTAMP,
                last_error = #{lastError}
            WHERE connector_name = #{connectorName}
            """)
    int markFailed(@Param("connectorName") String connectorName, @Param("lastError") String lastError);
}
