package com.yunye.mncdc.mapper;

import com.yunye.mncdc.entity.PendingTransactionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PendingTransactionMapper {

    @Insert("""
            INSERT INTO pending_tx (
                transaction_id,
                connector_name,
                binlog_filename,
                next_position,
                payload_json,
                blocked_tables,
                blocked_schema_binlog_file,
                blocked_schema_next_position,
                status
            ) VALUES (
                #{transactionId},
                #{connectorName},
                #{binlogFilename},
                #{nextPosition},
                #{payloadJson},
                #{blockedTables},
                #{blockedSchemaBinlogFile},
                #{blockedSchemaNextPosition},
                #{status}
            )
            """)
    int insert(PendingTransactionEntity entity);

    @Select("""
            SELECT transaction_id, connector_name, binlog_filename, next_position, payload_json, blocked_tables,
                   blocked_schema_binlog_file, blocked_schema_next_position, status
            FROM pending_tx
            WHERE status = 'PENDING'
              AND blocked_schema_binlog_file = #{blockedSchemaBinlogFile}
              AND blocked_schema_next_position = #{blockedSchemaNextPosition}
              AND blocked_tables LIKE CONCAT('%', #{tableKey}, '%')
            ORDER BY binlog_filename ASC, next_position ASC
            """)
    @Results({
            @Result(column = "transaction_id", property = "transactionId"),
            @Result(column = "connector_name", property = "connectorName"),
            @Result(column = "binlog_filename", property = "binlogFilename"),
            @Result(column = "next_position", property = "nextPosition"),
            @Result(column = "payload_json", property = "payloadJson"),
            @Result(column = "blocked_tables", property = "blockedTables"),
            @Result(column = "blocked_schema_binlog_file", property = "blockedSchemaBinlogFile"),
            @Result(column = "blocked_schema_next_position", property = "blockedSchemaNextPosition"),
            @Result(column = "status", property = "status")
    })
    List<PendingTransactionEntity> selectReplayCandidates(
            @Param("tableKey") String tableKey,
            @Param("blockedSchemaBinlogFile") String blockedSchemaBinlogFile,
            @Param("blockedSchemaNextPosition") Long blockedSchemaNextPosition
    );

    @Update("""
            UPDATE pending_tx
            SET status = 'REPLAYED'
            WHERE transaction_id = #{transactionId}
            """)
    int markReplayed(@Param("transactionId") String transactionId);
}
