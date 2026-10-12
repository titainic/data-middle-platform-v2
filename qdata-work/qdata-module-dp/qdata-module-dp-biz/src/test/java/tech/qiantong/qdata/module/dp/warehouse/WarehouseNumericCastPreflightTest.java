package tech.qiantong.qdata.module.dp.warehouse;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WarehouseNumericCastPreflightTest {
    private static WarehouseService service() {
        return new WarehouseService(mock(org.springframework.jdbc.core.JdbcTemplate.class),
            mock(org.springframework.transaction.support.TransactionTemplate.class),
            mock(tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService.class),
            mock(tech.qiantong.qdata.common.database.DataSourceFactory.class),
            mock(WarehouseSpaceService.class));
    }
    private static WarehouseDefinition definition() {
        var definition = new WarehouseDefinition();
        definition.setSelectSql("SELECT CAST(v * 1000000000000 AS DECIMAL(8,4)) amount FROM dw.ods_a");
        return definition;
    }
    @Test void fullReadOnlyScanConsumesAllColumnsBeforeTheWritePath() throws Exception {
        Statement statement=mock(Statement.class);
        ResultSet rows=mock(ResultSet.class);
        ResultSetMetaData metadata=mock(ResultSetMetaData.class);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(rows.next()).thenReturn(true,true,false);
        when(rows.getObject(1)).thenReturn(BigDecimal.ONE,BigDecimal.TWO);
        ReflectionTestUtils.invokeMethod(service(),"verifyNumericCastsBeforeInsert",statement,definition());
        verify(rows,times(3)).next();
        verify(rows,times(2)).getObject(1);
        verify(statement,never()).execute(anyString());
    }
    @Test void hiveCastAssertionRejectsInputWithoutSendingAnInsert() throws Exception {
        Statement statement=mock(Statement.class);
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("ASSERT_TRUE(): assertion failed"));
        var failure=assertThrows(WarehouseInputRejected.class,
            ()->ReflectionTestUtils.invokeMethod(service(),"verifyNumericCastsBeforeInsert",statement,definition()));
        assertTrue(failure.getMessage().contains("写入目标前拒绝"));
        verify(statement,never()).execute(anyString());
    }
    @Test void nonFiniteFloatingOutputIsRejectedEvenWithoutAnExplicitCast() throws Exception {
        var definition=new WarehouseDefinition();
        definition.setSelectSql("SELECT v AS value FROM dw.ods_a");
        var column=new WarehouseDefinition.Column();column.setName("value");column.setType("DOUBLE");
        definition.setColumns(List.of(column));
        Statement statement=mock(Statement.class);
        ResultSet rows=mock(ResultSet.class);
        ResultSetMetaData metadata=mock(ResultSetMetaData.class);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(rows.next()).thenReturn(true);
        when(rows.getObject(1)).thenReturn(Double.POSITIVE_INFINITY);
        var failure=assertThrows(WarehouseInputRejected.class,
            ()->ReflectionTestUtils.invokeMethod(service(),"verifyNumericCastsBeforeInsert",statement,definition));
        assertTrue(failure.getMessage().contains("NaN 或 Infinity"));
        verify(statement,never()).execute(anyString());
    }
}
