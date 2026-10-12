package tech.qiantong.qdata.module.dp.warehouse;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ServingRowCodecTest {
    @Test void qualityPolicyMustCoverEveryDwdProduct(){assertThrows(IllegalArgumentException.class,()->WarehouseServingService.requireCoverage(List.of(4L,5L,6L),List.of(4L)));assertThrows(IllegalArgumentException.class,()->WarehouseServingService.requireCoverage(List.of(),List.of(4L)));assertDoesNotThrow(()->WarehouseServingService.requireCoverage(List.of(4L,5L,6L),List.of(6L,4L,5L)));}
    private WarehouseDefinition.Column column(String type){var column=new WarehouseDefinition.Column();column.setName("value");column.setType(type);return column;}
    @Test void canonicalizesDecimalScaleWithoutLosingPrecision(){assertEquals("9007199254740993.123456",ServingRowCodec.value("9007199254740993.123456000","DECIMAL(30,9)"));assertEquals("3.14",ServingRowCodec.value("3.14000","DECIMAL(10,5)"));}
    @Test void preservesTimestampNanosecondsAndUnicode(){assertEquals("STRING",ServingRowCodec.type("TIMESTAMP"));assertEquals("2026-09-10 01:02:03.123456789",ServingRowCodec.response("2026-09-10 01:02:03.123456789","TIMESTAMP"));assertEquals("水质🌊",ServingRowCodec.response("水质🌊","STRING"));}
    @Test void hashIgnoresOrderButPreservesDuplicatesNullAndEmpty(){var columns=List.of(column("STRING"));var rows=List.of(List.of("a"),List.of("b"),List.of("a"));assertEquals(ServingRowCodec.fingerprint(rows,columns),ServingRowCodec.fingerprint(List.of(rows.get(1),rows.get(0),rows.get(2)),columns));assertNotEquals(ServingRowCodec.fingerprint(rows,columns),ServingRowCodec.fingerprint(List.of(rows.get(0),rows.get(1)),columns));assertNotEquals(ServingRowCodec.fingerprint(List.of(Arrays.asList((String)null)),columns),ServingRowCodec.fingerprint(List.of(List.of("")),columns));}
    @Test void numericBooleanAndDecimalResponsesKeepTheirTypes(){assertEquals(9007199254740993L,ServingRowCodec.response("9007199254740993","BIGINT"));assertEquals(new java.math.BigDecimal("0.123456789"),ServingRowCodec.response("0.123456789","DECIMAL(18,9)"));assertEquals(true,ServingRowCodec.response("1","BOOLEAN"));assertNull(ServingRowCodec.response(null,"STRING"));}
}
