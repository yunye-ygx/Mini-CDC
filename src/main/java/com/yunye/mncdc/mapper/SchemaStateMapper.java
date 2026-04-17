package com.yunye.mncdc.mapper;

import com.yunye.mncdc.entity.SchemaStateEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SchemaStateMapper {

    @Insert("""
            INSERT INTO schema_state (
                table_key,
                database_name,
                table_name,
                status,
                schema_binlog_file,
                schema_next_position,
                ddl_type,
                ddl_sql
            ) VALUES (
                #{tableKey},
                #{databaseName},
                #{tableName},
                #{status},
                #{schemaBinlogFile},
                #{schemaNextPosition},
                #{ddlType},
                #{ddlSql}
            )
            ON DUPLICATE KEY UPDATE
                status = VALUES(status),
                schema_binlog_file = VALUES(schema_binlog_file),
                schema_next_position = VALUES(schema_next_position),
                ddl_type = VALUES(ddl_type),
                ddl_sql = VALUES(ddl_sql)
            """)
    int insertOrUpdate(SchemaStateEntity entity);

    @Select({
            "<script>",
            "SELECT table_key, database_name, table_name, status, schema_binlog_file, schema_next_position, ddl_type, ddl_sql",
            "FROM schema_state",
            "WHERE table_key IN",
            "<foreach collection='tableKeys' item='tableKey' open='(' separator=',' close=')'>",
            "#{tableKey}",
            "</foreach>",
            "</script>"
    })
    @Results({
            @Result(column = "table_key", property = "tableKey"),
            @Result(column = "database_name", property = "databaseName"),
            @Result(column = "table_name", property = "tableName"),
            @Result(column = "status", property = "status"),
            @Result(column = "schema_binlog_file", property = "schemaBinlogFile"),
            @Result(column = "schema_next_position", property = "schemaNextPosition"),
            @Result(column = "ddl_type", property = "ddlType"),
            @Result(column = "ddl_sql", property = "ddlSql")
    })
    List<SchemaStateEntity> selectByTableKeys(@Param("tableKeys") List<String> tableKeys);

    @Select("""
            SELECT table_key, database_name, table_name, status, schema_binlog_file, schema_next_position, ddl_type, ddl_sql
            FROM schema_state
            ORDER BY database_name ASC, table_name ASC
            """)
    @Results({
            @Result(column = "table_key", property = "tableKey"),
            @Result(column = "database_name", property = "databaseName"),
            @Result(column = "table_name", property = "tableName"),
            @Result(column = "status", property = "status"),
            @Result(column = "schema_binlog_file", property = "schemaBinlogFile"),
            @Result(column = "schema_next_position", property = "schemaNextPosition"),
            @Result(column = "ddl_type", property = "ddlType"),
            @Result(column = "ddl_sql", property = "ddlSql")
    })
    List<SchemaStateEntity> selectAll();
}
