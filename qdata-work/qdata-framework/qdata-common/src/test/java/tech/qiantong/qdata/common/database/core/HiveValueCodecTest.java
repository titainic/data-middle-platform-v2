package tech.qiantong.qdata.common.database.core;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class HiveValueCodecTest {
    @Test void stringRetainsLeadingZerosAndWhitespace(){assertEquals("001",HiveValueCodec.convert("001","STRING"));assertEquals(" water ",HiveValueCodec.convert(" water ","STRING"));}
    @Test void decimalRoundingUsesHalfUpAndNormalizesNegativeZero(){assertEquals(new BigDecimal("2.68"),HiveValueCodec.convert("2.675","DECIMAL(4,2)"));assertEquals(new BigDecimal("-2.68"),HiveValueCodec.convert("-2.675","DECIMAL(4,2)"));assertEquals(new BigDecimal("0.00"),HiveValueCodec.convert("-0.004","DECIMAL(4,2)"));}
    @Test void boundaryDecimalRemainsRepresentable(){assertEquals(new BigDecimal("999.99"),HiveValueCodec.convert("999.994","DECIMAL(5,2)"));}
    @ParameterizedTest @CsvSource(value={"128|TINYINT","32768|SMALLINT","2147483648|INT","9223372036854775808|BIGINT","1.5|INT","9.995|DECIMAL(3,2)","1000|DECIMAL(5,2)","1|DECIMAL(4,5)","1e40|FLOAT","1e309|DOUBLE","NaN|DOUBLE","Infinity|DOUBLE","maybe|BOOLEAN","2024-02-30|DATE","2023-02-29 12:00:00|TIMESTAMP","2024-01-01 24:00:00|TIMESTAMP","2024-01-01T00:00:00Z|TIMESTAMP","abc|VARCHAR(2)","abc|CHAR(2)"},delimiter='|')
    void invalidConversionIsExplicit(String input,String type){assertThrows(IllegalArgumentException.class,()->HiveValueCodec.convert(input,type));}
    @Test void booleanFalseIsNotTreatedAsANonemptyString(){assertEquals(false,HiveValueCodec.convert("false","BOOLEAN"));assertEquals(true,HiveValueCodec.convert("1","BOOLEAN"));assertEquals(1,HiveValueCodec.convert(true,"INT"));}
    @Test void leapDateAndNanosecondsAreRetained(){assertEquals(java.sql.Date.valueOf("2024-02-29"),HiveValueCodec.convert("2024-02-29","DATE"));assertEquals(java.sql.Timestamp.valueOf("2024-02-29 23:59:59.123456789"),HiveValueCodec.convert("2024-02-29T23:59:59.123456789","TIMESTAMP"));}
    @Test void typedJavaDatesRemainCompatible(){assertEquals(java.sql.Date.valueOf("2024-02-29"),HiveValueCodec.convert(LocalDateTime.of(2024,2,29,23,0),"DATE"));assertEquals(java.sql.Timestamp.valueOf("2024-02-29 00:00:00"),HiveValueCodec.convert(LocalDate.of(2024,2,29),"TIMESTAMP"));}
    @Test void boundedTextCountsUnicodeCharacters(){assertEquals("😀a",HiveValueCodec.convert("😀a","VARCHAR(2)"));}
    @Test void errorDoesNotEchoPotentiallySensitiveCell(){String cell="private-cell-value";var error=assertThrows(IllegalArgumentException.class,()->HiveValueCodec.convert(cell,"INT"));assertFalse(error.getMessage().contains(cell));assertNull(error.getCause());}
    @Test void extremeExponentsAreHandledWithoutHugeDecimalAllocation(){assertEquals(new BigDecimal("0.00"),HiveValueCodec.convert("1e-999999999","DECIMAL(10,2)"));assertEquals(new BigDecimal("0.00"),HiveValueCodec.convert("0e999999999","DECIMAL(10,2)"));assertThrows(IllegalArgumentException.class,()->HiveValueCodec.convert("1e999999999","BIGINT"));assertThrows(IllegalArgumentException.class,()->HiveValueCodec.convert("1e999999999","DECIMAL(38,0)"));}
    @Test void floatingUnderflowAndOversizedNumericCellsFailExplicitly(){assertThrows(IllegalArgumentException.class,()->HiveValueCodec.convert("1e-400","DOUBLE"));assertThrows(IllegalArgumentException.class,()->HiveValueCodec.convert("1e-100","FLOAT"));assertThrows(IllegalArgumentException.class,()->HiveValueCodec.convert("1".repeat(10000),"DECIMAL(38,0)"));}
    @Test void nullRemainsNullAndBinaryCannotBecomeAnObjectAddress(){assertNull(HiveValueCodec.convert(null,"INT"));assertThrows(IllegalArgumentException.class,()->HiveValueCodec.convert(new byte[]{1,2},"STRING"));}
}
