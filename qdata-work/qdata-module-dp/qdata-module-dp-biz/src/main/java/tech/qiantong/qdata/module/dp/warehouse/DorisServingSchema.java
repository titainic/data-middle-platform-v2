package tech.qiantong.qdata.module.dp.warehouse;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Fail before publication if the physical serving schema no longer implements the frozen contract. */
final class DorisServingSchema {
    private static final Pattern DECIMAL=Pattern.compile("DECIMAL\\((\\d+),(\\d+)\\)");
    static void verify(ResultSetMetaData metadata,List<WarehouseDefinition.Column> columns)throws SQLException {
        if(metadata.getColumnCount()!=columns.size())throw new IllegalArgumentException("Doris字段数量与服务契约不一致");
        for(int i=0;i<columns.size();i++) {
            var column=columns.get(i);String type=ServingRowCodec.type(column.getType());int jdbc=metadata.getColumnType(i+1);
            if(!column.getName().equalsIgnoreCase(metadata.getColumnLabel(i+1)))throw new IllegalArgumentException("Doris字段顺序与服务契约不一致");
            var decimal=DECIMAL.matcher(type);boolean valid;
            if(decimal.matches())valid=(jdbc==Types.DECIMAL || jdbc==Types.NUMERIC) && metadata.getPrecision(i+1)==Integer.parseInt(decimal.group(1)) && metadata.getScale(i+1)==Integer.parseInt(decimal.group(2));
            else valid=switch(type){
                case "STRING" -> Set.of(Types.VARCHAR,Types.LONGVARCHAR,Types.CHAR,Types.NVARCHAR,Types.LONGNVARCHAR).contains(jdbc);
                case "BIGINT" -> jdbc==Types.BIGINT;
                case "INT" -> jdbc==Types.INTEGER;
                case "SMALLINT" -> jdbc==Types.SMALLINT;
                case "TINYINT" -> jdbc==Types.TINYINT;
                case "FLOAT" -> jdbc==Types.FLOAT || jdbc==Types.REAL;
                case "DOUBLE" -> jdbc==Types.DOUBLE;
                case "DATE" -> jdbc==Types.DATE;
                case "BOOLEAN" -> jdbc==Types.BOOLEAN || jdbc==Types.BIT || jdbc==Types.TINYINT;
                default -> false;
            };
            if(!valid)throw new IllegalArgumentException("Doris字段类型或精度已变化："+column.getName());
        }
    }
}
