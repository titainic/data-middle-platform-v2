package tech.qiantong.qdata.spark.etl.writer;

import org.junit.jupiter.api.Test;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HiveJdbcValueBoundaryTest {
    static class RecordingJdbc {
        int prepared,executed;String sql;Map<Integer,Object> values=new LinkedHashMap<>();
        int[] types;String[] names;int precision=5,scale=2;
        RecordingJdbc(int[] types,String[] names){this.types=types;this.names=names;}
        @SuppressWarnings("unchecked") <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
        Object nothing(Method method){if(method.getReturnType()==boolean.class)return false;if(method.getReturnType()==int.class)return 0;if(method.getReturnType()==long.class)return 0L;return null;}
        Connection connection(){
            ResultSetMetaData metadata=proxy(ResultSetMetaData.class,(p,m,a)->switch(m.getName()){
                case "getColumnCount"->types.length;case "getColumnType"->types[(Integer)a[0]-1];case "getColumnTypeName"->names[(Integer)a[0]-1];case "getPrecision"->precision;case "getScale"->scale;default->nothing(m);});
            ResultSet rows=proxy(ResultSet.class,(p,m,a)->m.getName().equals("getMetaData")?metadata:nothing(m));
            Statement statement=proxy(Statement.class,(p,m,a)->m.getName().equals("executeQuery")?rows:nothing(m));
            PreparedStatement insert=proxy(PreparedStatement.class,(p,m,a)->{
                if(Set.of("setObject","setDate","setTimestamp").contains(m.getName()))values.put((Integer)a[0],a[1]);
                if(m.getName().equals("setNull"))values.put((Integer)a[0],null);
                if(m.getName().equals("executeUpdate")){executed++;return 1;}return nothing(m);
            });
            return proxy(Connection.class,(p,m,a)->{if(m.getName().equals("createStatement"))return statement;if(m.getName().equals("prepareStatement")){prepared++;sql=(String)a[0];return insert;}return nothing(m);});
        }
    }
    @Test void appendBindsValidatedTypesAndPreservesStringIdentity()throws Exception{
        var db=new RecordingJdbc(new int[]{Types.VARCHAR,Types.DECIMAL,Types.TIMESTAMP,Types.BOOLEAN},new String[]{"string","decimal","timestamp","boolean"});
        long rows=HiveJdbcWriter.write(db.connection(),List.<Row>of(RowFactory.create("001","7.105","2024-02-29 23:59:59","false")).iterator(),"dwd_iot_db.fixture",List.of("device_id","reading","event_time","enabled"),2,500,List.of(),List.of());
        assertEquals(1,rows);assertEquals(1,db.executed);assertEquals("001",db.values.get(1));assertEquals(new java.math.BigDecimal("7.11"),db.values.get(2));assertEquals(Timestamp.valueOf("2024-02-29 23:59:59"),db.values.get(3));assertEquals(false,db.values.get(4));assertTrue(db.sql.startsWith("INSERT INTO TABLE"));assertFalse(db.sql.contains("OVERWRITE"));
    }
    @Test void overflowInBatchFailsBeforeAnyInsertIsPrepared(){var db=new RecordingJdbc(new int[]{Types.DECIMAL},new String[]{"decimal"});assertThrows(SQLException.class,()->HiveJdbcWriter.write(db.connection(),List.<Row>of(RowFactory.create("1.00"),RowFactory.create("1000.00")).iterator(),"dwd_iot_db.fixture",List.of("reading"),2,500,List.of(),List.of()));assertEquals(0,db.prepared);assertEquals(0,db.executed);}
    @Test void invalidDateCannotBecomeASuccessfulNull(){var db=new RecordingJdbc(new int[]{Types.DATE},new String[]{"date"});assertThrows(SQLException.class,()->HiveJdbcWriter.write(db.connection(),List.<Row>of(RowFactory.create("2024-02-30")).iterator(),"ods_iot_db.fixture",List.of("event_date"),2,500,List.of(),List.of()));assertEquals(0,db.prepared);assertEquals(0,db.executed);}
    @Test void varcharWidthIsPreservedFromJdbcMetadata(){var db=new RecordingJdbc(new int[]{Types.VARCHAR},new String[]{"varchar"});db.precision=2;assertThrows(SQLException.class,()->HiveJdbcWriter.write(db.connection(),List.<Row>of(RowFactory.create("abc")).iterator(),"ods_iot_db.fixture",List.of("code"),2,500,List.of(),List.of()));assertEquals(0,db.executed);}
    @Test void nullRemainsAnExplicitJdbcNull()throws Exception{var db=new RecordingJdbc(new int[]{Types.INTEGER},new String[]{"int"});HiveJdbcWriter.write(db.connection(),List.<Row>of(RowFactory.create((Object)null)).iterator(),"ods_iot_db.fixture",List.of("optional"),2,500,List.of(),List.of());assertTrue(db.values.containsKey(1));assertNull(db.values.get(1));}
}
