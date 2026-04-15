package com.yunye.mncdc.mapper;

import com.yunye.mncdc.entity.FullSyncTaskEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FullSyncTaskMapper {

    @Insert("""
            INSERT INTO full_sync_task (
                connector_name,
                database_name,
                table_name,
                status,
                cutover_binlog_filename,
                cutover_binlog_position,
                last_sent_pk,
                started_at,
                finished_at,
                last_error
            ) VALUES (
                #{connectorName},
                #{databaseName},
                #{tableName},
                #{status},
                #{cutoverBinlogFilename},
                #{cutoverBinlogPosition},
                #{lastSentPk},
                #{startedAt},
                #{finishedAt},
                #{lastError}
            )
            ON DUPLICATE KEY UPDATE
                status = VALUES(status),
                cutover_binlog_filename = VALUES(cutover_binlog_filename),
                cutover_binlog_position = VALUES(cutover_binlog_position),
                last_sent_pk = VALUES(last_sent_pk),
                started_at = VALUES(started_at),
                finished_at = VALUES(finished_at),
                last_error = VALUES(last_error)
            """)
    int insertOrUpdate(FullSyncTaskEntity entity);

    @Select("""
            SELECT COUNT(1)
            FROM full_sync_task
            WHERE connector_name = #{connectorName}
              AND database_name = #{databaseName}
              AND table_name = #{tableName}
            """)
    int countByTaskKey(
            @Param("connectorName") String connectorName,
            @Param("databaseName") String databaseName,
            @Param("tableName") String tableName
    );

    @Update("""
            UPDATE full_sync_task
            SET last_sent_pk = #{lastSentPk}
            WHERE connector_name = #{connectorName}
              AND database_name = #{databaseName}
              AND table_name = #{tableName}
            """)
    int updateLastSentPk(
            @Param("connectorName") String connectorName,
            @Param("databaseName") String databaseName,
            @Param("tableName") String tableName,
            @Param("lastSentPk") String lastSentPk
    );

    @Update("""
            UPDATE full_sync_task
            SET status = 'COMPLETED',
                finished_at = CURRENT_TIMESTAMP,
                last_error = NULL
            WHERE connector_name = #{connectorName}
              AND database_name = #{databaseName}
              AND table_name = #{tableName}
            """)
    int markCompleted(
            @Param("connectorName") String connectorName,
            @Param("databaseName") String databaseName,
            @Param("tableName") String tableName
    );

    @Update("""
            UPDATE full_sync_task
            SET status = 'FAILED',
                finished_at = CURRENT_TIMESTAMP,
                last_error = #{lastError}
            WHERE connector_name = #{connectorName}
              AND database_name = #{databaseName}
              AND table_name = #{tableName}
            """)
    int markFailed(
            @Param("connectorName") String connectorName,
            @Param("databaseName") String databaseName,
            @Param("tableName") String tableName,
            @Param("lastError") String lastError
    );
}
