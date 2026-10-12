package tech.qiantong.qdata.common.database.core;

import java.util.Locale;
import java.util.regex.Pattern;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;

/** A Hive namespace and short table name, independent of a connection's default database. */
public record HiveTableIdentity(String databaseName, String tableName) {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]{0,127}");

    public HiveTableIdentity {
        databaseName = identifier(databaseName);
        tableName = identifier(tableName);
    }

    public static String identifier(String value) {
        if (value != null && value.startsWith("`") && value.endsWith("`") && value.length() > 2)
            value = value.substring(1, value.length() - 1);
        if (value == null || !IDENTIFIER.matcher(value).matches())
            throw new IllegalArgumentException("Hive 库表标识只能包含字母、数字和下划线，且不能以数字开头");
        return value.toLowerCase(Locale.ROOT);
    }

    public static HiveTableIdentity resolve(String tableName, String defaultDatabase) {
        if (tableName == null) throw new IllegalArgumentException("Hive 表名不能为空");
        String[] parts = tableName.split("\\.", -1);
        if (parts.length == 1) return new HiveTableIdentity(defaultDatabase, parts[0]);
        if (parts.length == 2) return new HiveTableIdentity(parts[0], parts[1]);
        throw new IllegalArgumentException("Hive 表身份必须是 table 或 database.table");
    }

    public String qualified() { return "`" + databaseName + "`.`" + tableName + "`"; }
    public String canonical() { return databaseName + "." + tableName; }
    public String databaseSql() { return "`" + databaseName + "`"; }

    public DbQueryProperty property(DbQueryProperty connection) {
        DbQueryProperty resolved = connection.copy();
        resolved.setDbName(databaseName);
        return resolved;
    }
}
