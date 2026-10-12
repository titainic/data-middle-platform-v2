package tech.qiantong.qdata.module.da.service.asset.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.module.dp.api.model.dto.DpModelRespDTO;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseColumnLineageTest {
    DpModelRespDTO model(long id,String table,String layer,List<String> columns,String sql,Long... parents){
        var model=new DpModelRespDTO();model.setId(id);model.setDatasourceId(9L);model.setDatabaseName(table.split("\\.")[0]);model.setModelName(table.split("\\.")[1]);model.setDatasourceConfig("{\"password\":\"must-not-leak\"}");
        model.setWarehouseConfig(JSON.toJSONString(Map.of("tableName",model.getModelName(),"databaseName",model.getDatabaseName(),"spaceId",1,"revision",3,"layer",layer,"columns",columns.stream().map(name->Map.of("name",name,"type","STRING")).toList(),"selectSql",sql,"upstreamIds",List.of(parents),"dimensions",List.of())));return model;
    }
    WarehouseColumnLineage.Result resolve(DpModelRespDTO... models){return new WarehouseColumnLineage(List.of(models),"ods").resolve();}
    JSONObject field(WarehouseColumnLineage.Result result,long model,String field){return result.fields().stream().filter(row->row.getLongValue("modelId")==model&&row.getString("columnName").equals(field)).findFirst().orElseThrow();}
    Set<String> sources(JSONObject field,String key){Set<String> result=new TreeSet<>();for(Object item:field.getJSONArray(key)){var ref=(JSONObject)JSON.toJSON(item);result.add(ref.getString("tableName")+"."+ref.getString("columnName"));}return result;}
    @Test void cteCastAndAggregateTraceToTheImmediatePhysicalInput(){
        var raw=model(1,"ods.raw","ODS",List.of("id","reading"),"");var output=model(2,"dwd.result","DWD",List.of("score"),"WITH a AS (SELECT id,CAST(reading AS DECIMAL(10,2)) AS value FROM ods.raw), b AS (SELECT AVG(value) AS average_value FROM a) SELECT average_value/2 AS score FROM b",1L);
        var result=resolve(raw,output);var field=field(result,2,"score");assertEquals(Set.of("ods.raw.reading"),sources(field,"valueSources"));assertTrue(result.diagnostics().isEmpty());assertTrue(field.getJSONArray("expressions").size()>=3);assertFalse(JSON.toJSONString(result).contains("must-not-leak"));
    }
    @Test void unionRetainsEveryBranchInsteadOfOnlyTheFirst(){
        var a=model(1,"ods.a","ODS",List.of("value"),"");var b=model(2,"ods.b","ODS",List.of("value"),"");var c=model(3,"dwd.result","DWD",List.of("value"),"WITH x AS (SELECT value FROM ods.a UNION ALL SELECT value FROM ods.b) SELECT value FROM x",1L,2L);
        assertEquals(Set.of("ods.a.value","ods.b.value"),sources(field(resolve(a,b,c),3,"value"),"valueSources"));
    }
    @Test void rowCountUsesRowSourcesAndDoesNotInventAColumn(){
        var raw=model(1,"ods.a","ODS",List.of("id"),"");var output=model(2,"dwd.b","DWD",List.of("amount"),"SELECT COUNT(*) AS amount FROM ods.a",1L);var field=field(resolve(raw,output),2,"amount");assertEquals(Set.of("ods.a.*"),sources(field,"valueSources"));
    }
    @Test void filterAndGroupKeysRemainSeparateFromValueInputs(){
        var raw=model(1,"ods.a","ODS",List.of("id","reading"),"");var output=model(2,"dwd.b","DWD",List.of("average"),"SELECT AVG(reading) AS average FROM ods.a WHERE id<>'skip' GROUP BY id",1L);var field=field(resolve(raw,output),2,"average");assertEquals(Set.of("ods.a.reading"),sources(field,"valueSources"));assertEquals(Set.of("ods.a.id"),sources(field,"conditionSources"));
    }
    @Test void constantsRemainGeneratedButRetainTheirRowPopulation(){
        var raw=model(1,"ods.a","ODS",List.of("id"),"");var output=model(2,"dwd.b","DWD",List.of("kind"),"SELECT 'water' AS kind FROM ods.a",1L);var field=field(resolve(raw,output),2,"kind");assertTrue(sources(field,"valueSources").isEmpty());assertEquals(Set.of("ods.a.*"),sources(field,"rowSources"));assertEquals("GENERATED",field.getString("originKind"));
    }
    @Test void starExpansionPreservesDeclaredFieldIdentities(){
        var raw=model(1,"ods.a","ODS",List.of("id","value"),"");var output=model(2,"dwd.b","DWD",List.of("id","value"),"WITH x AS (SELECT id,value FROM ods.a) SELECT * FROM x",1L);var result=resolve(raw,output);assertEquals(2,result.resolvedDerived());assertEquals(Set.of("ods.a.id"),sources(field(result,2,"id"),"valueSources"));
    }
    @Test void cteShadowsARealTableWithTheSameName(){
        var raw=model(1,"ods.a","ODS",List.of("value"),"");var output=model(2,"dwd.x","DWD",List.of("value"),"WITH x AS (SELECT value FROM ods.a) SELECT value FROM x",1L);assertEquals(Set.of("ods.a.value"),sources(field(resolve(raw,output),2,"value"),"valueSources"));
    }
    @Test void ambiguousJoinCannotChooseTheFirstFieldSilently(){
        var a=model(1,"ods.a","ODS",List.of("id","value"),"");var b=model(2,"ods.b","ODS",List.of("id","value"),"");var output=model(3,"dwd.c","DWD",List.of("value"),"SELECT value FROM ods.a a JOIN ods.b b ON a.id=b.id",1L,2L);var result=resolve(a,b,output);assertEquals("UNRESOLVED",field(result,3,"value").getString("resolution"));assertFalse(result.diagnostics().isEmpty());
    }
    @Test void unknownFieldAndUndeclaredTableRemainUnresolved(){
        var raw=model(1,"ods.a","ODS",List.of("id"),"");var unknown=model(2,"dwd.b","DWD",List.of("id"),"SELECT absent AS id FROM ods.a",1L);assertEquals(0,resolve(raw,unknown).resolvedDerived());var foreign=model(3,"dwd.c","DWD",List.of("id"),"SELECT id FROM private.data",1L);assertEquals(0,resolve(raw,foreign).resolvedDerived());
    }
    @Test void windowOrderingIsPartOfTheValueDerivation(){
        var raw=model(1,"ods.a","ODS",List.of("id","event_time"),"");var output=model(2,"dwd.b","DWD",List.of("rank_value"),"SELECT ROW_NUMBER() OVER(PARTITION BY id ORDER BY event_time) AS rank_value FROM ods.a",1L);assertEquals(Set.of("ods.a.id","ods.a.event_time"),sources(field(resolve(raw,output),2,"rank_value"),"valueSources"));
    }
}
