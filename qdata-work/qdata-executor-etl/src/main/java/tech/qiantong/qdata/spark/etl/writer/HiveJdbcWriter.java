package tech.qiantong.qdata.spark.etl.writer;

import org.apache.spark.sql.Row;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Hive JDBC has no addBatch/commit support. Stream bounded multi-row INSERTs instead. */
public final class HiveJdbcWriter {
    private HiveJdbcWriter() { }

    public static long write(Connection connection, Iterator<Row> rows, String table,
                             List<String> columns, int mode, Integer requestedBatchSize,
                             List<Object> preSql, List<Object> postSql) throws SQLException {
        if (mode != 1 && mode != 2) {
            throw new SQLException("Hive supports full overwrite and append only; upsert is unsupported");
        }
        if (columns == null || columns.isEmpty()) {
            throw new SQLException("Hive output requires mapped columns");
        }
        int batchSize = requestedBatchSize == null ? 500 : requestedBatchSize;
        if (batchSize < 1) {
            throw new SQLException("Hive batch size must be positive");
        }
        batchSize = Math.min(batchSize, 1000);
        String columnSql = columns.stream().map(HiveJdbcWriter::quote).collect(Collectors.joining(","));
        String target = quoteTable(table);
        String staging = mode == 1 ? quoteTable(table + "_qdata_" + UUID.randomUUID().toString().replace("-", "")) : target;
        boolean stageCreated = false;
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            List<String> types = columnTypes(statement, target, columnSql);
            executeScripts(statement, preSql);
            if (mode == 1) {
                statement.execute("CREATE TABLE " + staging + " LIKE " + target);
                stageCreated = true;
            }
            long written = 0;
            List<Row> batch = new ArrayList<>(batchSize);
            while (rows.hasNext()) {
                Row row = rows.next();
                if (row.size() != columns.size()) {
                    throw new SQLException("Hive output column count does not match input row");
                }
                batch.add(row);
                if (batch.size() == batchSize) {
                    insert(connection, staging, columnSql, types, batch);
                    written += batch.size();
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                insert(connection, staging, columnSql, types, batch);
                written += batch.size();
            }
            if (mode == 1) {
                // Preserve destination schema, partitions and grants; publish only after all input succeeds.
                overwrite(connection, statement, target, staging, columns, columnSql);
            }
            executeScripts(statement, postSql);
            return written;
        } catch (SQLException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            if (stageCreated) {
                try (Statement cleanup = connection.createStatement()) {
                    cleanup.execute("DROP TABLE IF EXISTS " + staging);
                } catch (SQLException cleanupFailure) {
                    if (failure != null) {
                        failure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    private static void overwrite(Connection connection, Statement statement, String target, String staging,
                                  List<String> columns, String columnSql) throws SQLException {
        List<String> partitionColumns = new ArrayList<>();
        boolean partitionSection = false;
        try (ResultSet result = statement.executeQuery("DESCRIBE " + target)) {
            while (result.next()) {
                String name = result.getString(1);
                if (name == null) continue;
                name = name.trim();
                if (name.equals("# Partition Information")) partitionSection = true;
                else if (partitionSection && !name.isEmpty() && !name.startsWith("#")) partitionColumns.add(name);
            }
        }
        if (partitionColumns.isEmpty()) {
            statement.execute("INSERT OVERWRITE TABLE " + target + " (" + columnSql + ") SELECT "
                    + columnSql + " FROM " + staging);
            return;
        }
        List<String> dataColumns = columns.stream()
                .filter(column -> partitionColumns.stream().noneMatch(column::equalsIgnoreCase)).collect(Collectors.toList());
        if (dataColumns.isEmpty()) throw new SQLException("Hive overwrite requires a non-partition output column");
        java.util.Set<String> partitions = new java.util.LinkedHashSet<>();
        for (String table : List.of(target, staging)) {
            try (ResultSet result = statement.executeQuery("SHOW PARTITIONS " + table)) {
                while (result.next()) partitions.add(result.getString(1));
            }
        }
        String dataSql = dataColumns.stream().map(HiveJdbcWriter::quote).collect(Collectors.joining(","));
        String partitionSql = partitionColumns.stream().map(column -> quote(column) + "=?").collect(Collectors.joining(","));
        String whereSql = partitionColumns.stream().map(column -> quote(column) + " <=> ?").collect(Collectors.joining(" AND "));
        // Hive SerDe overwrite is dynamic: partitions absent from the new data would survive.
        // Explicit static overwrites also empty old partitions. Publication across partitions is non-transactional.
        String sql = "INSERT OVERWRITE TABLE " + target + " PARTITION (" + partitionSql + ") (" + dataSql
                + ") SELECT " + dataSql + " FROM " + staging + " WHERE " + whereSql;
        for (String partition : partitions) {
            java.util.Map<String, String> values = new java.util.HashMap<>();
            for (String entry : partition.split("/")) {
                String[] pair = entry.split("=", 2);
                if (pair.length != 2) throw new SQLException("Invalid Hive partition specification");
                values.put(unescapePartition(pair[0]), unescapePartition(pair[1]));
            }
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                int index = 1;
                for (int copy = 0; copy < 2; copy++) {
                    for (String column : partitionColumns) {
                        if (!values.containsKey(column)) throw new SQLException("Missing Hive partition value: " + column);
                        String value = values.get(column);
                        if ("__HIVE_DEFAULT_PARTITION__".equals(value)) insert.setNull(index++, Types.NULL);
                        else insert.setString(index++, value);
                    }
                }
                insert.executeUpdate();
            }
        }
    }

    private static String unescapePartition(String value) {
        return java.net.URLDecoder.decode(value.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void insert(Connection connection, String table, String columns, List<String> types,
                               List<Row> rows) throws SQLException {
        int width = types.size();
        String tuple = "(" + types.stream().map(type -> "CAST(? AS " + type + ")")
                .collect(Collectors.joining(",")) + ")";
        String sql = "INSERT INTO TABLE " + table + " (" + columns + ") VALUES "
                + String.join(",", Collections.nCopies(rows.size(), tuple));
        List<List<Object>> validated = new ArrayList<>(rows.size());
        for (int rowIndex=0;rowIndex<rows.size();rowIndex++) {
            Row row=rows.get(rowIndex);List<Object> converted=new ArrayList<>(width);
            for(int col=0;col<width;col++) {
                try {converted.add(tech.qiantong.qdata.common.database.core.HiveValueCodec.convert(row.isNullAt(col)?null:row.get(col),types.get(col)));}
                catch(IllegalArgumentException failure){throw new SQLException("Hive写入批内第"+(rowIndex+1)+"行第"+(col+1)+"列："+failure.getMessage());}
            }
            validated.add(converted);
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (List<Object> row : validated) {
                for (int col = 0; col < width; col++) {
                    Object value = row.get(col);
                    if (value == null) {
                        statement.setNull(index++, java.sql.Types.NULL);
                    } else if (value instanceof java.sql.Date) {
                        statement.setDate(index++, (java.sql.Date) value);
                    } else if (value instanceof java.sql.Timestamp) {
                        statement.setTimestamp(index++, (java.sql.Timestamp) value);
                    } else if (value instanceof java.time.Instant) {
                        statement.setTimestamp(index++, java.sql.Timestamp.from((java.time.Instant) value));
                    } else {
                        statement.setObject(index++, value);
                    }
                }
            }
            statement.executeUpdate();
        }
    }

    private static List<String> columnTypes(Statement statement, String table, String columns) throws SQLException {
        List<String> types = new ArrayList<>();
        try (ResultSet result = statement.executeQuery("SELECT " + columns + " FROM " + table + " WHERE 1=0")) {
            ResultSetMetaData metadata = result.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                String type;
                switch (metadata.getColumnType(i)) {
                    case Types.TINYINT: type = "TINYINT"; break;
                    case Types.SMALLINT: type = "SMALLINT"; break;
                    case Types.INTEGER: type = "INT"; break;
                    case Types.BIGINT: type = "BIGINT"; break;
                    case Types.FLOAT:
                    case Types.REAL: type = "FLOAT"; break;
                    case Types.DOUBLE: type = "DOUBLE"; break;
                    case Types.NUMERIC:
                    case Types.DECIMAL: type = "DECIMAL(" + metadata.getPrecision(i) + "," + metadata.getScale(i) + ")"; break;
                    case Types.BIT:
                    case Types.BOOLEAN: type = "BOOLEAN"; break;
                    case Types.DATE: type = "DATE"; break;
                    case Types.TIMESTAMP: type = "TIMESTAMP"; break;
                    case Types.CHAR: type = "CHAR(" + metadata.getPrecision(i) + ")"; break;
                    case Types.VARCHAR: type = "string".equalsIgnoreCase(metadata.getColumnTypeName(i)) ? "STRING" : "VARCHAR(" + metadata.getPrecision(i) + ")"; break;
                    case Types.LONGVARCHAR: type = "STRING"; break;
                    default: throw new SQLException("Hive JDBC import does not support column type: " + metadata.getColumnTypeName(i));
                }
                types.add(type);
            }
        }
        return types;
    }

    private static void executeScripts(Statement statement, List<Object> scripts) throws SQLException {
        if (scripts != null) {
            for (Object sql : scripts) {
                if (sql != null && !sql.toString().isBlank()) {
                    statement.execute(sql.toString());
                }
            }
        }
    }

    private static String quote(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    private static String quoteTable(String table) {
        return java.util.Arrays.stream(table.split("\\.")).map(HiveJdbcWriter::quote).collect(Collectors.joining("."));
    }
}
