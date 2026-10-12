package tech.qiantong.qdata.module.da.service.asset.impl;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.module.dp.api.model.dto.DpModelRespDTO;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WarehouseAssetLineageTest {
 private DpModelRespDTO model(long id, String upstream) {
  DpModelRespDTO m=new DpModelRespDTO();m.setId(id);m.setDatasourceId(9L);m.setDatasourceType("Hive");m.setIp("127.0.0.1");m.setPort(10000L);
  m.setDatasourceConfig("{\"dbname\":\"warehouse\",\"username\":\"reader\",\"password\":\"must-not-leak\"}");
  m.setWarehouseConfig("{\"tableName\":\"table_"+id+"\",\"upstreamIds\":"+upstream+",\"dimensions\":[]}");return m;
 }
 @Test void chainUsesRegisteredTablesAndDoesNotExposeConnectionSecrets() {
  var a=model(1,"[]");var b=model(2,"[1]");var c=model(3,"[2]");var all=Map.of(1L,a,2L,b,3L,c);
  var result=WarehouseAssetLineage.build(c,"warehouse",all::get);
  assertEquals(3,result.getTables().size());assertEquals(2,result.getRels().size());assertEquals(3L,result.getCurrentTable().getId());
  assertEquals("warehouse.table_3",result.getCurrentTable().getTableName());assertTrue(result.getTasks().isEmpty());
  assertFalse(com.alibaba.fastjson.JSON.toJSONString(result).contains("must-not-leak"));
 }
 @Test void crossDatasourceReferenceIsRejected() {
  var a=model(1,"[2]");var b=model(2,"[]");b.setDatasourceId(10L);
  assertThrows(IllegalArgumentException.class,()->WarehouseAssetLineage.build(a,"warehouse",id->b));
 }
 @Test void diamondDoesNotDuplicateSharedUpstream() {
  var a=model(1,"[]");var b=model(2,"[1]");var c=model(3,"[1]");var d=model(4,"[2,3]");var all=Map.of(1L,a,2L,b,3L,c,4L,d);
  var result=WarehouseAssetLineage.build(d,"warehouse",all::get);assertEquals(4,result.getTables().size());assertEquals(4,result.getRels().size());
 }
 @Test void missingOrCyclicSourceFailsInsteadOfInventingLineage() {
  var a=model(1,"[2]");assertThrows(IllegalArgumentException.class,()->WarehouseAssetLineage.build(a,"warehouse",id->null));
  var b=model(2,"[1]");var all=Map.of(1L,a,2L,b);assertThrows(IllegalArgumentException.class,()->WarehouseAssetLineage.build(a,"warehouse",all::get));
 }
 @Test void dimensionReferenceIsIncludedAndDeduplicated() {
  var a=model(1,"[]");var b=model(2,"[1]");b.setWarehouseConfig("{\"tableName\":\"table_2\",\"upstreamIds\":[1],\"dimensions\":[{\"modelId\":1},{\"modelId\":1}]}");
  var result=WarehouseAssetLineage.build(b,"warehouse",id->a);assertEquals(2,result.getTables().size());assertEquals(1,result.getRels().size());
 }
 @Test void sameShortTableNameRetainsItsOwnDatabaseAtEveryLayer() {
  var ods=model(1,"[]");var dwd=model(2,"[1]");
  ods.setWarehouseConfig("{\"tableName\":\"water_quality\",\"databaseName\":\"ods_iot_db\",\"upstreamIds\":[],\"dimensions\":[]}");
  dwd.setWarehouseConfig("{\"tableName\":\"water_quality\",\"databaseName\":\"dwd_iot_db\",\"upstreamIds\":[1],\"dimensions\":[]}");
  var result=WarehouseAssetLineage.build(dwd,"qdata_test",id->ods);
  assertEquals("dwd_iot_db.water_quality",result.getCurrentTable().getTableName());
  assertTrue(result.getTables().stream().anyMatch(t->"ods_iot_db.water_quality".equals(t.getTableName()) && "ods_iot_db".equals(t.getDbName())));
  assertEquals("PROCESSING",result.getRels().get(0).getJSONObject("properties").getString("kind"));
 }
 @Test void anotherWarehouseSpaceCannotEnterTheGraphThroughTheSameConnection(){
  var root=model(2,"[1]");var parent=model(1,"[]");
  var a=com.alibaba.fastjson.JSON.parseObject(root.getWarehouseConfig());a.put("spaceId",1);root.setWarehouseConfig(a.toJSONString());
  var b=com.alibaba.fastjson.JSON.parseObject(parent.getWarehouseConfig());b.put("spaceId",2);parent.setWarehouseConfig(b.toJSONString());
  assertThrows(IllegalArgumentException.class,()->WarehouseAssetLineage.build(root,"warehouse",id->parent));
 }
}
