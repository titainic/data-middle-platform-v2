package tech.qiantong.qdata.common.database.core;

import com.alibaba.druid.DbType;
import java.util.*;

/** Read-only Hive query boundary shared by browsing, parsing and exports. */
public final class HiveReadQuery {
    private HiveReadQuery() { }
    public static Set<String> tables(String sql,String defaultDatabase){
        Set<String> result=new LinkedHashSet<>();ReadOnlySql.tables(sql,DbType.hive).forEach(table->result.add(HiveTableIdentity.resolve(table,defaultDatabase).canonical()));return result;
    }
}
