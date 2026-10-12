package tech.qiantong.qdata.module.dp.warehouse;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DorisServingSchemaTest {
    private WarehouseDefinition.Column column(String name,String type){var column=new WarehouseDefinition.Column();column.setName(name);column.setType(type);return column;}
    @Test void changedDecimalPrecisionIsRejected()throws Exception {var metadata=mock(ResultSetMetaData.class);when(metadata.getColumnCount()).thenReturn(1);when(metadata.getColumnLabel(1)).thenReturn("avg_ph");when(metadata.getColumnType(1)).thenReturn(Types.DECIMAL);when(metadata.getPrecision(1)).thenReturn(6);when(metadata.getScale(1)).thenReturn(2);assertThrows(IllegalArgumentException.class,()->DorisServingSchema.verify(metadata,List.of(column("avg_ph","DECIMAL(6,3)"))));when(metadata.getScale(1)).thenReturn(3);assertDoesNotThrow(()->DorisServingSchema.verify(metadata,List.of(column("avg_ph","DECIMAL(6,3)"))));}
    @Test void narrowedIntegerAndReorderedFieldsAreRejected()throws Exception {var metadata=mock(ResultSetMetaData.class);when(metadata.getColumnCount()).thenReturn(1);when(metadata.getColumnLabel(1)).thenReturn("count");when(metadata.getColumnType(1)).thenReturn(Types.INTEGER);assertThrows(IllegalArgumentException.class,()->DorisServingSchema.verify(metadata,List.of(column("count","BIGINT"))));when(metadata.getColumnType(1)).thenReturn(Types.BIGINT);assertThrows(IllegalArgumentException.class,()->DorisServingSchema.verify(metadata,List.of(column("another","BIGINT"))));}
    @Test void timestampUsesTextToRetainNanoseconds()throws Exception {var metadata=mock(ResultSetMetaData.class);when(metadata.getColumnCount()).thenReturn(1);when(metadata.getColumnLabel(1)).thenReturn("created_at");when(metadata.getColumnType(1)).thenReturn(Types.LONGVARCHAR);assertDoesNotThrow(()->DorisServingSchema.verify(metadata,List.of(column("created_at","TIMESTAMP"))));}
}
