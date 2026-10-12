/*
 * Copyright © 2025-present Jiangsu Qiantong Technology Co., Ltd.
 *
 * This file is part of qData Data Middle Platform (Open Source Edition).
 *
 * qData is licensed under Apache License 2.0 with additional qData terms.
 * You may use qData for commercial purposes, but you may not remove, hide,
 * modify, or replace the qData logo, copyright notices, license notices,
 * or attribution information without separate commercial license from
 * Jiangsu Qiantong Technology Co., Ltd.
 *
 * White-label use, OEM distribution, rebranding, or presenting qData as
 * another product requires separate commercial authorization from
 * Jiangsu Qiantong Technology Co., Ltd.
 *
 * Business License: https://community.qdata.tech/business/policy.html
 * See the LICENSE file in the project root for full license information.
 */

package tech.qiantong.qdata.common.database.dialect;

import org.springframework.jdbc.core.RowMapper;
import java.util.ArrayList;
import java.util.List;

import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.core.DbColumn;
import tech.qiantong.qdata.common.database.core.DbName;
import tech.qiantong.qdata.common.database.core.DbTable;
import tech.qiantong.qdata.common.database.core.HiveTableIdentity;
import tech.qiantong.qdata.common.database.utils.DatabaseUtil;
import tech.qiantong.qdata.common.utils.StringUtils;

/**
 * Hive dialect, verified against a Hive 4.2 / Kyuubi 1.11 (Spark engine) cluster.
 * Kyuubi/Spark SHOW TABLES returns "namespace | tableName | isTemporary",
 * native HiveServer2 returns "tab_name"; both are handled by the table mapper.
 * information_schema is not exposed through the Thrift endpoint, so metadata
 * is collected with SHOW TABLES / DESCRIBE / DESCRIBE FORMATTED.
 */
public class HiveDialect extends AbstractDbDialect {

    @Override
    public RowMapper<DbTable> tableMapper() {
        return (rs, rowNum) -> {
            DbTable entity = new DbTable();
            // Spark/Kyuubi: tableName column; native HS2: tab_name
            if (DatabaseUtil.hasColumn(rs, "tableName")) {
                entity.setTableName(rs.getString("tableName"));
            } else if (DatabaseUtil.hasColumn(rs, "tab_name")) {
                entity.setTableName(rs.getString("tab_name"));
            } else {
                entity.setTableName(rs.getString(1));
            }
            return entity;
        };
    }

    @Override
    public RowMapper<DbColumn> columnMapper() {
        // DESCRIBE output columns: col_name | data_type | comment
        return (rs, rowNum) -> {
            DbColumn entity = new DbColumn();
            entity.setColName(rs.getString("col_name"));
            entity.setDataType(rs.getString("data_type"));
            String comment = rs.getString("comment");
            if (comment != null && !"null".equalsIgnoreCase(comment.trim())) {
                entity.setColComment(comment);
            }
            // Hive columns are all nullable and never auto-increment
            entity.setNullable(true);
            entity.setColKey(false);
            entity.setColPosition(rowNum + 1);
            return entity;
        };
    }

    /** DESCRIBE repeats partition columns below separator/header rows. */
    public static java.util.List<DbColumn> normalizeColumns(java.util.List<DbColumn> columns) {
        java.util.Map<String, DbColumn> unique = new java.util.LinkedHashMap<>();
        for (DbColumn column : columns) {
            if (column == null || StringUtils.isBlank(column.getColName())
                    || StringUtils.isBlank(column.getDataType()) || column.getColName().trim().startsWith("#")) {
                continue;
            }
            String name = column.getColName().trim();
            column.setColName(name);
            unique.putIfAbsent(name.toLowerCase(java.util.Locale.ROOT), column);
        }
        java.util.List<DbColumn> result = new java.util.ArrayList<>(unique.values());
        for (int i = 0; i < result.size(); i++) {
            result.get(i).setColPosition(i + 1);
        }
        return result;
    }

    @Override
    public String columns(DbQueryProperty dbQueryProperty, String tableName) {
        return "DESCRIBE " + qualify(dbQueryProperty, tableName);
    }

    @Override
    public String columns(String dbName, String tableName) {
        return "DESCRIBE " + HiveTableIdentity.resolve(tableName, dbName).qualified();
    }

    @Override
    public String tables(DbQueryProperty dbQueryProperty) {
        if (StringUtils.isNotBlank(dbQueryProperty.getDbName())) {
            return "SHOW TABLES IN `" + dbQueryProperty.getDbName() + "`";
        }
        return "SHOW TABLES";
    }

    @Override
    public String tables(String dbName) {
        return "SHOW TABLES IN `" + dbName + "`";
    }

    @Override
    public String tablesComment(DbQueryProperty dbQueryProperty, String tableName) {
        // DESCRIBE FORMATTED output: col_name | data_type | comment.
        // The "# Detailed Table Information" section contains a row with
        // col_name='comment' whose data_type holds the table comment; this is
        // exactly what AbstractDbQueryFactory's HIVE branch parses.
        return "DESCRIBE FORMATTED " + qualify(dbQueryProperty, tableName);
    }

    @Override
    public String getPkColumnNames(DbQueryProperty dbQueryProperty, String tableName) {
        // Hive has no primary keys
        return "";
    }

    @Override
    public String getDataStorageSize(String dbName) {
        // Hive has no information_schema size aggregation; storage stats are
        // collected per table via DESCRIBE FORMATTED (totalSize) by the mc module.
        return "SELECT 0 AS \"usedSizeMb\"";
    }

    @Override
    public String getDbName() {
        return "SHOW DATABASES";
    }

    /**
     * Database tree browsing: SHOW DATABASES returns a single "namespace" column
     * (Kyuubi/Spark) or "database_name" (native HS2), so read the first column.
     * Hive namespaces are flat (database -> table, no schema layer), so the
     * hierarchy must be reported as single-level; a two-level declaration makes
     * callers expand a parent x child cross product and re-collect every table.
     */
    @Override
    public String getDbName(DbName dbName) {
        return "SHOW DATABASES";
    }

    @Override
    public RowMapper<DbName> firstLevelMapper(int level) {
        return (rs, rowNum) -> DbName.builder()
                .dbName(rs.getString(1))
                .level(level)
                .totalLevels(1)
                .children(null)
                .build();
    }

    /**
     * No catalog-wide column dump exists on Hive/Kyuubi (information_schema is
     * not exposed). Return a valid statement that yields no rows so the batch
     * loader degrades to per-table DESCRIBE instead of throwing on a null SQL.
     */
    @Override
    public String getDbColumns(DbQueryProperty property) {
        return "SELECT '' AS col_name, '' AS data_type, '' AS comment WHERE 1=0";
    }

    @Override
    public String getPkColumnNames(DbQueryProperty dbQueryProperty) {
        return "";
    }

    @Override
    public String getInsertOrUpdateSql(String tableName, String where, String tableFieldName, String tableFieldValue, String setValue) {
        // HiveQL has no upsert; append-only INSERT (used by API write-back scenarios)
        String sql = "INSERT INTO TABLE {tableName} ({tableFieldName}) VALUES ({tableFieldValue})";
        return sql
                .replace("{tableName}", tableName)
                .replace("{tableFieldName}", tableFieldName)
                .replace("{tableFieldValue}", tableFieldValue);
    }

    @Override
    public String generateCheckTableExistsSQL(DbQueryProperty dbQueryProperty, String tableName) {
        // AbstractDbQueryFactory's HIVE branch runs this via queryForList:
        // non-empty result set means the table exists
        HiveTableIdentity identity = HiveTableIdentity.resolve(tableName, dbQueryProperty.getDbName());
        return "SHOW TABLES IN " + identity.databaseSql() + " LIKE '" + identity.tableName() + "'";
    }

    @Override
    public String buildQuerySqlFields(java.util.List<DbColumn> columns, String tableName, DbQueryProperty dbQueryProperty) {
        if (columns == null || columns.isEmpty()) {
            return "SELECT * FROM " + qualify(dbQueryProperty, tableName);
        }
        String fields = columns.stream()
                .map(column -> "`" + column.getColName() + "`")
                .collect(java.util.stream.Collectors.joining(", "));
        return "SELECT " + fields + " FROM " + qualify(dbQueryProperty, tableName);
    }

    @Override
    public List<String> someInternalSqlGenerator(DbQueryProperty dbQueryProperty, String tableName, String tableComment, List<DbColumn> dbColumnList) {
        List<String> sqlList = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE IF NOT EXISTS ").append(qualify(dbQueryProperty, tableName)).append(" (\n");
        for (DbColumn column : dbColumnList) {
            sql.append("  `").append(column.getColName()).append("` ").append(column.getDataType());
            if (column.getColComment() != null && !column.getColComment().isEmpty()) {
                sql.append(" COMMENT '").append(column.getColComment().replace("'", "\\'")).append("'");
            }
            sql.append(",\n");
        }
        if (sql.toString().endsWith(",\n")) {
            sql.setLength(sql.length() - 2);
        }
        sql.append("\n)");
        if (tableComment != null && !tableComment.isEmpty()) {
            sql.append(" COMMENT '").append(tableComment.replace("'", "\\'")).append("'");
        }
        sqlList.add(sql.toString());
        return sqlList;
    }

    @Override
    public List<String> validateSpecification(String tableName, String tableComment, List<DbColumn> columns) {
        return null;
    }

    @Override
    public String buildPaginationSql(String originalSql, long offset, long count) {
        // Keep a single outer LIMIT/OFFSET. A user query that already
        // carries its own LIMIT must be wrapped as a derived table, otherwise
        // appending another LIMIT produces "... LIMIT 20 LIMIT 20" parse errors.
        String sql = originalSql.trim();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (wrapsForPagination(sql, offset)) {
            String page = "SELECT * FROM ( " + sql + " ) qdata_page LIMIT " + count;
            if (offset > 0) {
                page += " OFFSET " + offset;
            }
            return page;
        }
        return sql + " LIMIT " + count;
    }

    public boolean wrapsForPagination(String originalSql, long offset) {
        String sql = originalSql.trim();
        while (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
        return offset > 0 || sql.matches("(?is).*\\blimit\\s+\\d+(\\s*,\\s*\\d+)?\\s*");
    }

    @Override
    public String buildTableNameByDbType(DbQueryProperty dbQueryProperty, String tableName) {
        return qualify(dbQueryProperty, tableName);
    }

    @Override
    public String getTableName(DbQueryProperty property, String tableName) {
        return HiveTableIdentity.resolve(tableName, property.getDbName()).canonical();
    }

    private String qualify(DbQueryProperty dbQueryProperty, String tableName) {
        return HiveTableIdentity.resolve(tableName, dbQueryProperty.getDbName()).qualified();
    }
}
