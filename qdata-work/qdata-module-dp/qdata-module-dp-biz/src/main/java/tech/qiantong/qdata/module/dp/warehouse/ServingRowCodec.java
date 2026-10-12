package tech.qiantong.qdata.module.dp.warehouse;

import com.alibaba.fastjson2.JSON;
import java.math.BigDecimal;
import java.util.*;

/** Preserve numeric meaning, nulls, Unicode and Hive's full timestamp text across JDBC protocols. */
final class ServingRowCodec {
    static String type(String hive) {
        String type=WarehouseSql.type(hive);
        if(type.equals("STRING") || type.startsWith("CHAR") || type.startsWith("VARCHAR") || type.equals("TIMESTAMP"))return "STRING";
        return type;
    }
    static String value(String value,String type) {
        if(value==null)return null;
        String normalized=WarehouseSql.type(type);
        if(normalized.startsWith("DECIMAL") || Set.of("TINYINT","SMALLINT","INT","BIGINT").contains(normalized))return new BigDecimal(value).stripTrailingZeros().toPlainString();
        if(Set.of("FLOAT","DOUBLE").contains(normalized)) {double number=Double.parseDouble(value);return number==0?"0":Double.toHexString(number);}
        if(normalized.equals("BOOLEAN"))return (value.equals("1") || value.equalsIgnoreCase("true"))?"true":"false";
        return value;
    }
    static String row(List<String> values,List<WarehouseDefinition.Column> columns) {
        List<String> normalized=new ArrayList<>();for(int i=0;i<columns.size();i++)normalized.add(value(values.get(i),columns.get(i).getType()));
        return JSON.toJSONString(normalized);
    }
    static String fingerprint(List<List<String>> rows,List<WarehouseDefinition.Column> columns) {
        return WarehouseDataFingerprint.sha(String.join("\n",rows.stream().map(row->row(row,columns)).sorted().toList()));
    }
    static Object response(String value,String type) {
        if(value==null)return null;
        String normalized=WarehouseSql.type(type);
        if(normalized.startsWith("DECIMAL"))return new BigDecimal(value);
        if(Set.of("TINYINT","SMALLINT","INT","BIGINT").contains(normalized))return Long.valueOf(value);
        if(Set.of("FLOAT","DOUBLE").contains(normalized))return Double.valueOf(value);
        if(normalized.equals("BOOLEAN"))return value.equals("1") || value.equalsIgnoreCase("true");
        return value;
    }
}
