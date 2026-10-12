package tech.qiantong.qdata.module.dp.warehouse;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class WarehouseSqlTest {
 @Test public void lineageIncludesNestedJoinAndCteSources() {
  assertEquals(Set.of("dw.ods_a","dw.ods_b"), WarehouseSql.readTables("WITH x AS (SELECT id FROM dw.ods_a) SELECT x.id FROM x JOIN (SELECT id FROM dw.ods_b) b ON x.id=b.id", "dw"));
 }
 @Test public void quotedQualifiedNamesAndStringLiterals() {
  assertEquals(Set.of("dw.dwd_a"),WarehouseSql.readTables("SELECT 'from ods_bad' AS note FROM `dw`.`dwd_a`", "dw"));
 }
 @Test public void rejectsWrites() {assertThrows(IllegalArgumentException.class, () -> {WarehouseSql.readTables("INSERT INTO x SELECT * FROM ods_a","dw");});}
 @Test public void rejectsMultipleStatements() {assertThrows(IllegalArgumentException.class, () -> {WarehouseSql.readTables("SELECT * FROM ods_a; DROP TABLE ods_a","dw");});}
 @Test public void rejectsSourceFreeSelect() {assertThrows(IllegalArgumentException.class, () -> {WarehouseSql.readTables("SELECT 1 AS x","dw");});}
 @Test public void externalFunctionsCannotHideBehindCommentsOrQuotedNames() {
  for(String function:List.of("reflect/**/", "java_method /* comment */", "reflect2", "`reflect`"))
   assertThrows(IllegalArgumentException.class,()->WarehouseSql.readTables("SELECT "+function+"('unit.class','method') FROM dw.ods_a","dw"),function);
 }
 @Test public void harmlessFunctionNamesInsideLiteralsAreNotRejected() {
  assertEquals(Set.of("dw.ods_a"),WarehouseSql.readTables("SELECT 'reflect(x)' AS note FROM dw.ods_a","dw"));
 }
 @Test public void rejectsUnsafeIdentifier() {assertThrows(IllegalArgumentException.class, () -> {WarehouseSql.id("foo`;DROP TABLE t");});}
 @Test public void rejectsDecimalScale() {assertThrows(IllegalArgumentException.class, () -> {WarehouseSql.type("DECIMAL(4,6)");});}
 @Test public void rejectsDuplicateFields() {assertThrows(IllegalArgumentException.class, () -> {var d=definition();d.getColumns().add(column("STATION","STRING"));WarehouseSql.validateColumns(d);});}
 @Test public void partitionFieldsAreLastAndLengthsPreserved() {
  var d=definition();var part=column("dt","STRING");part.setPartition(true);d.getColumns().add(0,part);d.getColumns().add(column("amount","DECIMAL(18,3)"));
  String ddl=WarehouseSql.ddl(d,"dw");assertTrue(ddl.contains("PARTITIONED BY (`dt` STRING"));assertTrue(ddl.contains("DECIMAL(18,3)"));assertTrue(ddl.contains("STORED AS PARQUET"));
  assertEquals("dt",WarehouseSql.ordered(d).get(2).getName());
 }
 @Test public void odsOnlyGeneratesIdentityAppend() {var d=definition();d.setRawTable("raw_rain");assertEquals("INSERT INTO TABLE `dw`.`ods_rain`\nSELECT `station` FROM `dw`.`raw_rain`",WarehouseSql.insert(d,"dw"));}
 @Test public void everyLayerUsesAppendWithoutOverwrite() {
  for(String layer:WarehouseSql.LAYERS) {
   var d=definition();d.setLayer(layer);d.setTableName(layer.toLowerCase()+"_rain");d.setRawTable("raw_rain");d.setSelectSql("SELECT station FROM dw.ods_rain;");
   String sql=WarehouseSql.insert(d,"dw");assertTrue(sql.startsWith("INSERT INTO TABLE "));assertFalse(sql.contains("OVERWRITE"));
  }
 }
 @Test public void orcDdlUsesSnappyCompression() {var d=definition();d.setStorage("ORC");assertTrue(WarehouseSql.ddl(d,"dw").contains("STORED AS ORC\nTBLPROPERTIES ('orc.compress'='SNAPPY')"));}
 @Test public void cteIsBeforeInsertInsteadOfInsideItsSelectClause() {
  var d=definition();d.setLayer("DWD");d.setTableName("dwd_rain");d.setSelectSql("WITH c AS (SELECT station FROM dw.ods_rain) SELECT station FROM c");
  String sql=WarehouseSql.insert(d,"dw");assertTrue(sql.startsWith("WITH "));assertTrue(sql.contains("INSERT INTO TABLE `dw`.`dwd_rain`"));assertEquals(1,sql.split("WITH ",-1).length-1);
  assertTrue(sql.indexOf("INSERT INTO")>sql.indexOf("FROM dw.ods_rain"));assertFalse(sql.contains("OVERWRITE"));
 }
 @Test public void cteSchemaCheckKeepsWithAtStatementScope() {
  var d=definition();d.setLayer("DWD");d.setSelectSql("WITH c AS (SELECT station FROM dw.ods_rain), x AS (SELECT station FROM c) SELECT station FROM x");
  String sql=WarehouseSql.schemaCheck(d,"dw");assertTrue(sql.startsWith("WITH "));assertTrue(sql.endsWith("warehouse_schema_check LIMIT 0"));assertFalse(sql.contains("FROM (WITH"));
  assertEquals(Set.of("dw.ods_rain"),WarehouseSql.readTables(sql,"dw"));
 }
 private WarehouseDefinition definition(){var d=new WarehouseDefinition();d.setName("雨情");d.setTableName("ods_rain");d.getColumns().add(column("station","STRING"));return d;}
 private WarehouseDefinition.Column column(String name,String type){var c=new WarehouseDefinition.Column();c.setName(name);c.setType(type);c.setSource("raw."+name);return c;}
}
