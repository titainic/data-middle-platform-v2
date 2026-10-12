package tech.qiantong.qdata.common.database;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.common.database.core.BrowserQueryValues;
import tech.qiantong.qdata.common.database.core.PageResult;
import tech.qiantong.qdata.common.database.query.MyRowMapper;
import java.math.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryNumericPrecisionTest {
    Object mapped(Object value)throws Exception{var rows=mock(ResultSet.class);var meta=mock(ResultSetMetaData.class);when(rows.getMetaData()).thenReturn(meta);when(meta.getColumnCount()).thenReturn(1);when(meta.getColumnLabel(1)).thenReturn("value");when(rows.getObject(1)).thenReturn(value);return new MyRowMapper().mapRow(rows,0).get("value");}
    @Test void databaseDecimalNeverNarrowsThroughBinaryFloatingPoint()throws Exception{var value=new BigDecimal("12345678901234567890.123456789");assertEquals(value,mapped(value));assertInstanceOf(BigDecimal.class,mapped(value));}
    @Test void unsignedIntegersDoNotWrapIntoNegativeLongs()throws Exception{var value=new BigInteger("18446744073709551615");assertEquals(value,mapped(value));}
    @Test void smallDatabaseIntegersKeepTheirEstablishedLongRepresentation()throws Exception{assertEquals(12L,mapped(BigInteger.valueOf(12)));assertEquals(Long.MIN_VALUE,mapped(BigInteger.valueOf(Long.MIN_VALUE)));}
    @Test void wideDecimalsUseExactBrowserText(){String value="12345678901234567890.123456789";assertEquals(value,BrowserQueryValues.convert(new BigDecimal(value)));}
    @Test void browserUnsafeIntegerBoundsAreExplicit(){assertEquals(9007199254740991L,BrowserQueryValues.convert(9007199254740991L));assertEquals("9007199254740992",BrowserQueryValues.convert(9007199254740992L));assertEquals("-9007199254740992",BrowserQueryValues.convert(-9007199254740992L));assertEquals(String.valueOf(Long.MIN_VALUE),BrowserQueryValues.convert(Long.MIN_VALUE));}
    @Test void anExactlyRepresentableButUnsafeDecimalIntegerStillUsesText(){assertEquals("9007199254740992",BrowserQueryValues.convert(new BigDecimal("9007199254740992")));}
    @Test void highPrecisionFractionsCannotLoseTheirLastDigits(){assertEquals("0.123456789012345678901",BrowserQueryValues.convert(new BigDecimal("0.123456789012345678901")));}
    @Test void usualDecimalsRemainJsonNumbersWithTheirDeclaredScale(){var value=new BigDecimal("7.120");assertSame(value,BrowserQueryValues.convert(value));}
    @Test void conversionCopiesNestedPagesWithoutChangingDatesNullsOrTheOriginal(){
        Map<String,Object> row=new LinkedHashMap<>();var value=new BigDecimal("12345678901234567890.123456789");var date=LocalDateTime.of(2026,9,22,1,2);row.put("value",value);row.put("date",date);row.put("empty",null);
        var input=new PageResult<>(2,List.of(row)).setPageNum(2).setPageSize(1);var output=(PageResult<?>)BrowserQueryValues.convert(input);var converted=(Map<?,?>)output.getData().get(0);
        assertEquals(2,output.getPageNum());assertEquals(1,output.getPageSize());assertEquals(2,output.getTotal());assertEquals(value.toPlainString(),converted.get("value"));assertSame(date,converted.get("date"));assertTrue(converted.containsKey("empty"));assertNull(converted.get("empty"));assertSame(value,row.get("value"));
    }
    @Test void extremeExponentsAndNonFiniteValuesHaveBoundedExplicitText(){assertEquals("1E+1000000",BrowserQueryValues.convert(new BigDecimal("1E+1000000")));assertEquals("NaN",BrowserQueryValues.convert(Double.NaN));assertEquals("Infinity",BrowserQueryValues.convert(Float.POSITIVE_INFINITY));}
}
