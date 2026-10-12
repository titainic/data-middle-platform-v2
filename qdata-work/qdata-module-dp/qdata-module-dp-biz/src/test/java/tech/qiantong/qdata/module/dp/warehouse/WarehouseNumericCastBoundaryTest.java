package tech.qiantong.qdata.module.dp.warehouse;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseNumericCastBoundaryTest {
 @Test void precisionOrScaleOutsideHiveDecimalLimitFailsBeforeMaterialization(){
  assertThrows(IllegalArgumentException.class,()->WarehouseSql.readTables("SELECT CAST(v AS DECIMAL(39,2)) amount FROM dw.ods_a","dw"));
  assertThrows(IllegalArgumentException.class,()->WarehouseSql.readTables("SELECT CAST(v AS DECIMAL(4,5)) amount FROM dw.ods_a","dw"));
 }
 @Test void constantOverflowIncludingRoundingIsRejected(){
  assertThrows(IllegalArgumentException.class,()->WarehouseSql.readTables("SELECT CAST('999.999' AS DECIMAL(5,2)) amount FROM dw.ods_a","dw"));
  assertThrows(IllegalArgumentException.class,()->WarehouseSql.readTables("SELECT CAST(2147483648 AS INT) amount FROM dw.ods_a","dw"));
  assertThrows(IllegalArgumentException.class,()->WarehouseSql.readTables("SELECT CAST('invalid' AS DECIMAL(8,3)) amount FROM dw.ods_a","dw"));
 }
 @Test void validConstantsAndDynamicCastsRetainTheExistingReadContract(){
  assertEquals(Set.of("dw.ods_a"),WarehouseSql.readTables("SELECT CAST('7.100' AS DECIMAL(9,3)) amount, CAST(v AS DECIMAL(18,6)) value FROM dw.ods_a","dw"));
 }
 @Test void floatingCastConstantsMustBeFinite(){
  for(String value:new String[]{"NaN","Infinity","1e309","not-a-number"})
   assertThrows(IllegalArgumentException.class,()->WarehouseSql.readTables("SELECT CAST('"+value+"' AS DOUBLE) amount FROM dw.ods_a","dw"));
  assertThrows(IllegalArgumentException.class,()->WarehouseSql.readTables("SELECT CAST('1e99' AS FLOAT) amount FROM dw.ods_a","dw"));
  assertEquals(Set.of("dw.ods_a"),WarehouseSql.readTables("SELECT CAST('1.25' AS DOUBLE) amount FROM dw.ods_a","dw"));
 }
 @Test void dynamicFloatingCastChecksNanAndInfinity(){
  String check=WarehouseSql.numericCastPreflight("SELECT CAST(v AS DOUBLE) amount FROM dw.ods_a");
  assertTrue(check.contains("UPPER(CAST("));
  for(String value:new String[]{"'NAN'","'INFINITY'","'-INFINITY'"})assertTrue(check.contains(value));
 }
 @Test void readOnlyPreflightGuardsDynamicCastsInCteAndUnionBeforeAnyInsert(){
  String sql="WITH cleaned AS (SELECT CAST(v AS DECIMAL(8,4)) value FROM dw.ods_a) "
      +"SELECT CAST(value * 1000000000000 AS DECIMAL(8,4)) amount FROM cleaned";
  String check=WarehouseSql.numericCastPreflight(sql);
  assertNotNull(check);
  assertTrue(check.startsWith("WITH "));
  assertEquals(2,check.split("assert_true",-1).length-1);
  assertFalse(check.toUpperCase().contains("INSERT "));
  String union=WarehouseSql.numericCastPreflight("SELECT CAST(v AS DECIMAL(8,4)) amount FROM dw.ods_a "
      +"UNION ALL SELECT CAST(v AS DECIMAL(8,4)) amount FROM dw.ods_b");
  assertEquals(2,union.split("assert_true",-1).length-1);
  assertNull(WarehouseSql.numericCastPreflight("SELECT v FROM dw.ods_a"));
 }
}
