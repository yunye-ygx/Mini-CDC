package com.yunye.mncdc.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TableMetadataMapper {

    @Select("""
            SELECT COLUMN_NAME
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = #{database}
              AND TABLE_NAME = #{table}
            ORDER BY ORDINAL_POSITION
            """)
    List<String> selectColumnNames(@Param("database") String database, @Param("table") String table);

    @Select("""
            SELECT COLUMN_NAME
            FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA = #{database}
              AND TABLE_NAME = #{table}
              AND CONSTRAINT_NAME = 'PRIMARY'
            ORDER BY ORDINAL_POSITION
            """)
    List<String> selectPrimaryKeyNames(@Param("database") String database, @Param("table") String table);
}
