package tech.qiantong.qdata.common.database;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tech.qiantong.qdata.common.database.dialect.*;
import tech.qiantong.qdata.common.database.query.AbstractDbQueryFactory;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HivePaginationColumnTest {
    Map<String,Object> read(DbDialect dialect,String sql,long offset,String driverLabel)throws Exception{
        var query=mock(AbstractDbQueryFactory.class,CALLS_REAL_METHODS);query.setDbDialect(dialect);doReturn(2).when(query).count(anyString());
        var jdbc=mock(JdbcTemplate.class);var field=AbstractDbQueryFactory.class.getDeclaredField("jdbcTemplate");field.setAccessible(true);field.set(query,jdbc);
        var rows=mock(ResultSet.class);var metadata=mock(ResultSetMetaData.class);when(rows.getMetaData()).thenReturn(metadata);when(metadata.getColumnCount()).thenReturn(1);when(metadata.getColumnLabel(1)).thenReturn(driverLabel);when(rows.getObject(1)).thenReturn("001");
        when(jdbc.query(anyString(),any(RowMapper.class))).thenAnswer(call->List.of(((RowMapper<Map<String,Object>>)call.getArgument(1)).mapRow(rows,0)));
        return query.queryByPage(sql,offset,1).getData().get(0);
    }
    @Test void secondPageKeepsTheFirstPageColumnContract()throws Exception{assertEquals(Map.of("device_id","001"),read(new HiveDialect(),"SELECT device_id FROM input_rows",1,"qdata_page.device_id"));}
    @Test void aUserLimitAlsoUsesTheStableOutputContract()throws Exception{assertEquals(Map.of("device_id","001"),read(new HiveDialect(),"SELECT device_id FROM input_rows LIMIT 5;",0,"qdata_page.device_id"));}
    @Test void unwrappedUserAliasIsNotRewritten()throws Exception{assertEquals(Map.of("qdata_page.device_id","001"),read(new HiveDialect(),"SELECT device_id AS `qdata_page.device_id` FROM input_rows",0,"qdata_page.device_id"));}
    @Test void onlyOneGeneratedQualifierIsRemoved()throws Exception{assertEquals(Map.of("qdata_page.device_id","001"),read(new HiveDialect(),"SELECT device_id AS `qdata_page.device_id` FROM input_rows",1,"QDATA_PAGE.qdata_page.device_id"));}
    @Test void otherDatabaseLabelsArePreserved()throws Exception{assertEquals(Map.of("qdata_page.device_id","001"),read(new MySqlDialect(),"SELECT device_id FROM input_rows",1,"qdata_page.device_id"));}
}
