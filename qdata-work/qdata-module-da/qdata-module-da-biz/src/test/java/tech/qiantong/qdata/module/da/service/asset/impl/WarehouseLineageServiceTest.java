package tech.qiantong.qdata.module.da.service.asset.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import tech.qiantong.qdata.module.da.service.asset.AssetProjectReadAccess;
import tech.qiantong.qdata.module.dp.api.model.dto.DpModelRespDTO;
import tech.qiantong.qdata.module.dpp.api.warehouse.WarehouseTaskGateway;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WarehouseLineageServiceTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);AssetProjectReadAccess reads=mock(AssetProjectReadAccess.class);ProjectPermissionService permissions=mock(ProjectPermissionService.class);WarehouseTaskGateway inputs=mock(WarehouseTaskGateway.class);
    WarehouseLineageService service=new WarehouseLineageService(jdbc,reads,permissions,inputs);
    Map<Long,DpModelRespDTO> models=new LinkedHashMap<>();Map<Long,Map<String,Object>> snapshots=new HashMap<>();JSONObject proof;
    @BeforeEach void setup(){
        var raw=model(1,"ods.events_snap","ODS",10,List.of("id","event_time","data_date"),"");var output=model(2,"dwd.result","DWD",11,List.of("id","tag"),"SELECT id,'restricted-value' AS tag FROM ods.events_snap",1L);models.put(1L,raw);models.put(2L,output);
        var original=JSON.parseObject(raw.getWarehouseConfig());original.put("id",30);original.put("tableName","events_input");
        proof=new JSONObject(true);proof.put("datasetId",10);proof.put("readModels",List.of(original));proof.put("receipts",List.of(Map.of("instanceId",501,"taskId",61,"taskVersion",1,"projectId",144,"datasourceId",9,"outputTable","ods.events_input","fileSha256","a".repeat(64),"fileRows",3,"originalFile","/profile/must-not-leak.csv")));
        for(var model:models.values()){
            Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("batch_id","fixed-batch");snapshot.put("definition_json",model.getWarehouseConfig());snapshot.put("template_model_id",model.getId()+100);snapshot.put("template_revision",7);snapshot.put("task_id",99L);snapshot.put("task_version",4);snapshot.put("node_code","node-"+model.getId());snapshot.put("status","SUCCESS");snapshot.put("input_json",JSON.toJSONString(List.of(proof)));snapshots.put(model.getId(),snapshot);
        }
        when(jdbc.queryForList(startsWith("SELECT s.batch_id"),anyLong())).thenAnswer(call->{var value=snapshots.get(call.getArgument(1));return value==null?List.of():List.of(value);});
        when(jdbc.queryForObject(startsWith("SELECT project_id"),eq(Long.class),eq(1L))).thenReturn(144L);when(permissions.has(144L,"dp:model:query")).thenReturn(true);
        when(inputs.inputLineage(501)).thenReturn(input(1));
    }
    WarehouseTaskGateway.InputLineage input(int version){return new WarehouseTaskGateway.InputLineage(501,61,version,144,9,"ods.events_input",List.of(new WarehouseTaskGateway.InputColumn("id",List.of("id"),List.of(),List.of()),new WarehouseTaskGateway.InputColumn("event_time",List.of("event_time"),List.of(),List.of()),new WarehouseTaskGateway.InputColumn("data_date",List.of("event_time"),List.of(),List.of(Map.of("operation","DATE_FORMAT_STD","configuration",Map.of("targetFormat","restricted-format"))))));}
    DpModelRespDTO model(long id,String table,String layer,long dataset,List<String> names,String sql,Long... upstream){
        var dto=new DpModelRespDTO();dto.setId(id);dto.setDatasourceId(9L);dto.setDatasetId(dataset);dto.setDatabaseName(table.split("\\.")[0]);dto.setModelName(table.split("\\.")[1]);dto.setDatasourceConfig("{\"password\":\"must-not-leak\"}");
        JSONObject config=new JSONObject(true);config.put("id",id);config.put("spaceId",1);config.put("datasetId",dataset);config.put("datasourceId",9);config.put("databaseName",dto.getDatabaseName());config.put("tableName",dto.getModelName());config.put("layer",layer);config.put("revision",3);config.put("columns",names.stream().map(name->Map.of("name",name,"type","STRING")).toList());config.put("selectSql",sql);config.put("upstreamIds",List.of(upstream));config.put("dimensions",List.of());dto.setWarehouseConfig(config.toJSONString());return dto;
    }
    JSONObject field(tech.qiantong.qdata.neo4j.dto.LineageDTO result,long model,String column){return result.getFields().stream().filter(f->f.getLongValue("modelId")==model&&f.getString("columnName").equals(column)).findFirst().orElseThrow();}
    void refreshProof(){snapshots.values().forEach(snapshot->snapshot.put("input_json",JSON.toJSONString(List.of(proof))));}
    @Test void fixedInputsAndPublishedTaskVersionsCompleteEveryFieldWithoutSecrets(){
        var result=service.build(models.get(2L),"ods",models::get);assertEquals("COMPLETE",result.getFieldCoverage().get("status"));assertEquals(5,result.getFields().size());var date=field(result,1,"data_date");assertEquals(List.of("event_time"),date.getJSONArray("inputOrigins").getJSONObject(0).getJSONArray("csvColumns").toJavaList(String.class));assertEquals("ods.events_input",date.getJSONArray("rowSources").getJSONObject(0).getString("tableName"));assertEquals("*",date.getJSONArray("rowSources").getJSONObject(0).getString("columnName"));assertEquals(4,date.getJSONObject("version").getIntValue("task_version"));assertFalse(JSON.toJSONString(result).contains("must-not-leak"));assertFalse(JSON.toJSONString(result).contains("/profile/"));verify(inputs,times(1)).inputLineage(501);verify(reads,times(1)).physicalRules(9L,"ods.events_input");
    }
    @Test void assetReadersWithoutModelQueryDoNotReceiveExpressionsOrLiteralParameters(){
        when(permissions.has(144L,"dp:model:query")).thenReturn(false);var result=service.build(models.get(2L),"ods",models::get);String json=JSON.toJSONString(result);assertEquals(false,result.getFieldCoverage().get("canReadExpressions"));assertFalse(json.contains("restricted-value"));assertFalse(json.contains("restricted-format"));assertEquals("COMPLETE",result.getFieldCoverage().get("status"));assertFalse(field(result,2,"id").getJSONArray("valueSources").isEmpty());
    }
    @Test void fixedDefinitionWinsOverLaterMutableConfiguration(){
        JSONObject changed=JSON.parseObject(models.get(2L).getWarehouseConfig());changed.put("revision",99);changed.put("selectSql","SELECT id,'changed-value' AS tag FROM ods.events_snap");models.get(2L).setWarehouseConfig(changed.toJSONString());
        var result=service.build(models.get(2L),"ods",models::get);assertEquals(3,field(result,2,"tag").getIntValue("modelRevision"));String json=JSON.toJSONString(result);assertTrue(json.contains("restricted-value"));assertFalse(json.contains("changed-value"));
    }
    @Test void upstreamReadFailureStopsBeforeReadingInputTaskDetails(){
        doThrow(new AccessDeniedException("denied")).when(reads).physicalRules(9L,"ods.events_snap");assertThrows(AccessDeniedException.class,()->service.build(models.get(2L),"ods",models::get));verifyNoInteractions(inputs);
    }
    @Test void inputVersionMismatchCannotClaimCompleteLineage(){
        when(inputs.inputLineage(501)).thenReturn(input(2));var result=service.build(models.get(2L),"ods",models::get);assertEquals("PARTIAL",result.getFieldCoverage().get("status"));assertEquals("UNRESOLVED",field(result,1,"id").getString("resolution"));
    }
    @Test void retrySnapshotKeepsItsActualReadTableAndOriginalCsvReceipt(){
        var original=proof.getJSONArray("readModels").getJSONObject(0);original.put("id",31);original.put("tableName","events_previous_snapshot");refreshProof();var result=service.build(models.get(2L),"ods",models::get);var field=field(result,1,"id");assertEquals("COMPLETE",result.getFieldCoverage().get("status"));assertEquals("ods.events_previous_snapshot",field.getJSONArray("valueSources").getJSONObject(0).getString("tableName"));assertEquals("ods.events_input",field.getJSONArray("inputOrigins").getJSONObject(0).getString("tableName"));
    }
    @Test void changedPhysicalIdentityIsRejected(){
        JSONObject corrupt=JSON.parseObject(String.valueOf(snapshots.get(2L).get("definition_json")));corrupt.put("datasourceId",77);snapshots.get(2L).put("definition_json",corrupt.toJSONString());assertThrows(IllegalArgumentException.class,()->service.build(models.get(2L),"ods",models::get));
    }
    @Test void unboundDefinitionsAreLabelledInsteadOfInventingTaskVersions(){
        snapshots.remove(2L);var result=service.build(models.get(2L),"ods",models::get);assertEquals("MODEL_DEFINITION",field(result,2,"id").getJSONObject("version").getString("basis"));assertNull(field(result,2,"id").getJSONObject("version").get("task_version"));assertEquals("MIXED_WITH_MODEL_DEFINITIONS",result.getFieldCoverage().get("versionBasis"));
    }
}
