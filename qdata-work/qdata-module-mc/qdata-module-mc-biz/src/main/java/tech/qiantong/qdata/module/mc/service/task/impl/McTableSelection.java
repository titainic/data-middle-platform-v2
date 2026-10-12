package tech.qiantong.qdata.module.mc.service.task.impl;

import tech.qiantong.qdata.common.database.core.DbTable;
import tech.qiantong.qdata.common.database.core.HiveTableIdentity;
import java.util.*;

/** Exact Hive version selection. Absence preserves the existing database collection mode. */
final class McTableSelection {
    private McTableSelection() { }

    static Set<String> parse(String value) {
        if (value == null || value.isBlank()) return Set.of();
        if (value.length() > 100_000) throw new IllegalArgumentException("指定表清单过长");
        Set<String> result = new TreeSet<>();
        for (String token : value.split("[,\\r\\n]+")) {
            String table = token.trim();
            if (table.isEmpty()) continue;
            if (!table.matches("[a-zA-Z_][a-zA-Z0-9_]*\\.[a-zA-Z_][a-zA-Z0-9_]*"))
                throw new IllegalArgumentException("指定表必须使用完整库表名，每行一张表，不支持通配符");
            result.add(HiveTableIdentity.resolve(table, null).canonical());
        }
        if (result.size() > 500) throw new IllegalArgumentException("一次最多指定500张表");
        return Collections.unmodifiableSet(result);
    }

    static String validate(String value, String dbType, String collectionScope, Collection<String> databases, String blacklist) {
        var selected = parse(value);
        if (selected.isEmpty()) return "";
        if (!"Hive".equalsIgnoreCase(dbType) || !"1".equals(collectionScope))
            throw new IllegalArgumentException("指定表采集仅支持Hive自定义数据库范围");
        Set<String> scope = new HashSet<>();
        for (String database : databases) scope.add(HiveTableIdentity.identifier(database));
        for (String table : selected) {
            String database = table.substring(0, table.indexOf('.'));
            if (!scope.contains(database)) throw new IllegalArgumentException("指定表不在所选数据库范围：" + table);
            if (McTaskServiceImpl.isInBlacklist(table, blacklist) || McTaskServiceImpl.isInBlacklist(database, blacklist))
                throw new IllegalArgumentException("指定表与排除清单冲突：" + table);
        }
        return String.join("\n", selected);
    }

    static boolean includes(String selection, String database, String table) {
        var selected = parse(selection);
        return selected.isEmpty() || selected.contains(HiveTableIdentity.resolve(table, database).canonical());
    }

    static List<DbTable> select(String selection, String database, List<DbTable> available) {
        var selected = parse(selection);
        if (selected.isEmpty()) return available;
        String prefix = HiveTableIdentity.identifier(database) + ".";
        Set<String> missing = new TreeSet<>();
        selected.stream().filter(t -> t.startsWith(prefix)).forEach(missing::add);
        List<DbTable> result = new ArrayList<>();
        for (DbTable table : available) {
            String identity = HiveTableIdentity.resolve(table.getTableName(), database).canonical();
            if (selected.contains(identity)) { result.add(table); missing.remove(identity); }
        }
        if (!missing.isEmpty()) throw new IllegalStateException("指定表不存在或当前连接不可见，保留既有元数据：" + String.join(",", missing));
        return result;
    }
}
