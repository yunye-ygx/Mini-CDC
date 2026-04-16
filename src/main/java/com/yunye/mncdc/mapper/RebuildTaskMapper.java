package com.yunye.mncdc.mapper;

import com.yunye.mncdc.entity.RebuildTaskEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.sql.Timestamp;

@Mapper
public interface RebuildTaskMapper {

    @Insert("""
            INSERT INTO rebuild_task (
                task_id,
                database_name,
                table_name,
                schema_binlog_file,
                schema_next_position,
                status,
                retry_count,
                last_error
            ) VALUES (
                #{taskId},
                #{databaseName},
                #{tableName},
                #{schemaBinlogFile},
                #{schemaNextPosition},
                #{status},
                #{retryCount},
                #{lastError}
            )
            """)
    int insert(RebuildTaskEntity entity);

    @Select("""
            SELECT task_id, database_name, table_name, schema_binlog_file, schema_next_position, status, retry_count, last_error
            FROM rebuild_task
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT 1
            """)
    @Results({
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "database_name", property = "databaseName"),
            @Result(column = "table_name", property = "tableName"),
            @Result(column = "schema_binlog_file", property = "schemaBinlogFile"),
            @Result(column = "schema_next_position", property = "schemaNextPosition"),
            @Result(column = "status", property = "status"),
            @Result(column = "retry_count", property = "retryCount"),
            @Result(column = "last_error", property = "lastError")
    })
    RebuildTaskEntity selectFirstPending();

    @Update("""
            UPDATE rebuild_task
            SET status = #{toStatus}
            WHERE task_id = #{taskId}
              AND status = #{fromStatus}
            """)
    int transitionStatus(
            @Param("taskId") String taskId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus
    );

    @Update("""
            UPDATE rebuild_task
            SET status = 'PENDING',
                retry_count = retry_count + 1,
                last_error = #{lastError}
            WHERE task_id = #{taskId}
              AND status = 'RUNNING'
            """)
    int requeue(@Param("taskId") String taskId, @Param("lastError") String lastError);

    @Update("""
            UPDATE rebuild_task
            SET status = 'FAILED',
                retry_count = retry_count + 1,
                last_error = #{lastError}
            WHERE task_id = #{taskId}
              AND status = 'RUNNING'
            """)
    int markFailed(@Param("taskId") String taskId, @Param("lastError") String lastError);

    @Update("""
            UPDATE rebuild_task
            SET status = 'PENDING',
                retry_count = retry_count + 1,
                last_error = 'Recovered stale RUNNING task for retry.'
            WHERE status = 'RUNNING'
              AND updated_at < #{cutoff}
            """)
    int resetExpiredRunningTasks(@Param("cutoff") Timestamp cutoff);

    @Update("""
            UPDATE rebuild_task
            SET status = #{status}
            WHERE task_id = #{taskId}
            """)
    int updateStatus(@Param("taskId") String taskId, @Param("status") String status);
}
