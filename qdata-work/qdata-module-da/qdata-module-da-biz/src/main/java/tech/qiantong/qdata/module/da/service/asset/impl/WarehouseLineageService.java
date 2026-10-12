package tech.qiantong.qdata.module.da.service.asset.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.database.core.HiveTableIdentity;
import tech.qiantong.qdata.common.utils.SecretRedactor;
import tech.qiantong.qdata.module.da.service.asset.AssetProjectReadAccess;
import tech.qiantong.qdata.module.dp.api.model.dto.DpModelRespDTO;
import tech.qiantong.qdata.module.dpp.api.warehouse.WarehouseTaskGateway;
import tech.qiantong.qdata.neo4j.dto.LineageDTO;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;
import java.util.function.Function;
import java.util.function.BiConsumer;

/** Authorized, fixed-version column provenance. No graph server or Hive connection is required. */
@Service
@RequiredArgsConstructor
public class WarehouseLineageService {
    private final JdbcTemplate jdbc;
    private final AssetProjectReadAccess reads;
    private final ProjectPermissionService permissions;
    private final WarehouseTaskGateway inputs;

    public LineageDTO build(DpModelRespDTO root,String fallbackDatabase,Function<Long,DpModelRespDTO> lookup) {
        Map<Long,DpModelRespDTO> definitions=new LinkedHashMap<>();Map<Long,Map<String,Object>> snapshots=new LinkedHashMap<>();
        Set<String> authorizedTables=new HashSet<>();BiConsumer<Long,String> authorize=(source,table)->{String key=source+":"+table;if(!authorizedTables.contains(key)){reads.physicalRules(source,table);authorizedTables.add(key);}};
        Function<Long,DpModelRespDTO> effective=id->{
            if(definitions.containsKey(id))return definitions.get(id);
            DpModelRespDTO current=Objects.equals(id,root.getId())?root:lookup.apply(id);if(current==null)return null;
            JSONObject config=JSON.parseObject(current.getWarehouseConfig());String database=config.getString("databaseName");if(database==null||database.isBlank())database=current.getDatabaseName();if(database==null||database.isBlank())database=fallbackDatabase;
            String table=HiveTableIdentity.resolve(config.getString("tableName"),database).canonical();authorize.accept(current.getDatasourceId(),table);
            var rows=jdbc.queryForList("SELECT s.batch_id,s.model_id,s.template_model_id,s.template_revision,s.definition_json,s.node_code,s.status,s.output_fingerprint,s.row_count,b.task_id,b.task_version,b.space_id,b.input_json FROM dp_warehouse_batch_step s JOIN dp_warehouse_batch b ON b.id=s.batch_id WHERE s.model_id=?",id);
            require(rows.size()<=1,"模型绑定了多个批次，无法确认字段版本");
            if(!rows.isEmpty()){
                var snapshot=rows.get(0);JSONObject fixed=JSON.parseObject(String.valueOf(snapshot.get("definition_json")));
                for(String key:List.of("datasourceId","datasetId","spaceId"))require(Objects.equals(config.getLong(key),fixed.getLong(key)),"固定模型的所属身份已变化");
                require(table.equals(HiveTableIdentity.resolve(fixed.getString("tableName"),fixed.getString("databaseName")).canonical()),"固定模型物理身份已变化");
                config=fixed;snapshots.put(id,snapshot);
            }
            var safe=new DpModelRespDTO();safe.setId(id);safe.setDatasourceId(current.getDatasourceId());safe.setDatabaseName(database);safe.setModelName(config.getString("tableName"));safe.setDatasetId(config.getLong("datasetId"));safe.setDatasourceName(current.getDatasourceName());safe.setWarehouseConfig(config.toJSONString());definitions.put(id,safe);return safe;
        };
        DpModelRespDTO selected=effective.apply(root.getId());LineageDTO result=WarehouseAssetLineage.build(selected,fallbackDatabase,effective);
        JSONObject rootConfig=JSON.parseObject(selected.getWarehouseConfig());Long spaceId=rootConfig.getLong("spaceId");
        Long projectId=spaceId==null?null:jdbc.queryForObject("SELECT project_id FROM dp_warehouse_space WHERE id=?",Long.class,spaceId);
        boolean expressions=permissions.isAdministrator() || projectId!=null && permissions.has(projectId,"dp:model:query");
        if(spaceId==null){result.setFieldCoverage(Map.of("status","UNAVAILABLE","canReadExpressions",expressions,"message","旧模型尚未绑定数仓空间，字段血缘需先完成纳管"));return result;}
        var resolved=new WarehouseColumnLineage(definitions.values(),fallbackDatabase).resolve();List<JSONObject> fields=resolved.fields();List<String> diagnostics=new ArrayList<>(resolved.diagnostics());
        Map<Long,WarehouseTaskGateway.InputLineage> inputCache=new HashMap<>();Map<String,List<JSONObject>> proofCache=new HashMap<>();
        for(JSONObject field:fields){
            long modelId=field.getLongValue("modelId");var snapshot=snapshots.get(modelId);JSONObject version=new JSONObject(true);
            version.put("basis",snapshot==null?"MODEL_DEFINITION":"FIXED_BATCH");version.put("modelRevision",field.get("modelRevision"));
            if(snapshot!=null)for(String key:List.of("batch_id","template_model_id","template_revision","task_id","task_version","node_code","status","output_fingerprint","row_count"))version.put(key,snapshot.get(key));
            field.put("version",version);
            if("RAW_BOUNDARY".equals(field.getString("resolution")) && snapshot!=null){
                try{
                    List<JSONObject> proofs=proofCache.computeIfAbsent(String.valueOf(snapshot.get("batch_id")),unused->JSON.parseArray(String.valueOf(snapshot.get("input_json")),JSONObject.class));
                    Long dataset=definitions.get(modelId).getDatasetId();List<JSONObject> matches=proofs.stream().filter(proof->Objects.equals(proof.getLong("datasetId"),dataset)).toList();require(matches.size()==1,"原始数据集输入证明不唯一");
                    bindInput(field,matches.get(0),root.getDatasourceId(),projectId,inputCache,authorize);
                }catch(AccessDeniedException denied){throw denied;}
                catch(RuntimeException failure){String message=SecretRedactor.redact(Objects.toString(failure.getMessage(),"接入字段版本尚未确认"));field.put("resolution","UNRESOLVED");field.put("message",message);diagnostics.add("模型#"+modelId+"."+field.getString("columnName")+"："+message);}
            }
            if(!expressions){field.put("expressions",List.of());if(field.containsKey("message"))field.put("message","字段来源尚未完整确认");for(Object origin:field.getJSONArray("inputOrigins"))((JSONObject)origin).remove("steps");}
        }
        long complete=fields.stream().filter(field->"RESOLVED".equals(field.getString("resolution"))).count();
        Map<String,Object> coverage=new LinkedHashMap<>();coverage.put("status",complete==resolved.declared()&&diagnostics.isEmpty()?"COMPLETE":"PARTIAL");coverage.put("declaredFields",resolved.declared());coverage.put("resolvedFields",complete);coverage.put("derivedFields",resolved.resolvedDerived());coverage.put("rawFields",resolved.rawFields());coverage.put("canReadExpressions",expressions);coverage.put("versionBasis",snapshots.size()==definitions.size()?"FIXED_BATCH":"MIXED_WITH_MODEL_DEFINITIONS");coverage.put("diagnostics",expressions?diagnostics:diagnostics.isEmpty()?List.of():List.of("部分字段来源尚未确认，请由有模型查看权限的用户核对"));
        result.setFields(fields);result.setFieldCoverage(coverage);return result;
    }

    private void bindInput(JSONObject field,JSONObject proof,long sourceId,long projectId,Map<Long,WarehouseTaskGateway.InputLineage> cache,BiConsumer<Long,String> authorize) {
        String column=field.getString("columnName"),targetType=field.getString("columnType");List<JSONObject> origins=new ArrayList<>();List<Map<String,Object>> sources=new ArrayList<>(),conditions=new ArrayList<>();
        Map<String,JSONObject> readModels=new LinkedHashMap<>();
        for(Object value:proof.getJSONArray("readModels")){
            var model=(JSONObject)value;String table=HiveTableIdentity.resolve(model.getString("tableName"),model.getString("databaseName")).canonical();
            require(model.getLongValue("id")>0 && model.getJSONArray("columns")!=null,"固定输入模型契约缺失");
            require(model.getLongValue("datasourceId")==sourceId && Objects.equals(model.getLong("datasetId"),proof.getLong("datasetId")),"接入来源连接或数据集不一致");
            require(model.getJSONArray("columns").stream().map(item->(JSONObject)item).anyMatch(item->column.equals(item.getString("name"))&&targetType.replaceAll("\\s+","").equalsIgnoreCase(Objects.toString(item.get("type"),"").replaceAll("\\s+",""))),"固定输入字段契约不一致");
            authorize.accept(sourceId,table);readModels.put(table,model);sources.add(Map.of("modelId",model.getLongValue("id"),"tableName",table,"columnName",column,"kind","INPUT_VERSION"));
        }
        require(!readModels.isEmpty(),"固定输入版本清单为空");
        var receipts=proof.getJSONArray("receipts");require(receipts!=null&&!receipts.isEmpty(),"固定输入缺少成功接入凭据");
        for(Object value:receipts){JSONObject receipt=(JSONObject)value;long instance=receipt.getLongValue("instanceId");var lineage=cache.computeIfAbsent(instance,inputs::inputLineage);
            require(receipt.getLongValue("projectId")==projectId && receipt.getLongValue("datasourceId")==sourceId && lineage.projectId()==projectId && lineage.datasourceId()==sourceId && lineage.taskId()==receipt.getLongValue("taskId") && lineage.taskVersion()==receipt.getIntValue("taskVersion") && lineage.outputTable().equals(receipt.getString("outputTable")),"接入任务版本或归属与固定证明不一致");
            require(receipt.getString("fileSha256")!=null && receipt.getString("fileSha256").matches("(?i)[0-9a-f]{64}"),"固定输入摘要无效");
            require(HiveTableIdentity.resolve(lineage.outputTable(),null).databaseName().equals(HiveTableIdentity.resolve(field.getString("tableName"),null).databaseName()),"接入来源不属于当前ODS命名空间");
            authorize.accept(sourceId,lineage.outputTable());var input=readModels.get(lineage.outputTable());
            var sourceColumn=lineage.columns().stream().filter(item->column.equals(item.name())).findFirst().orElseThrow(()->new IllegalArgumentException("接入输出缺少当前字段"));
            JSONObject origin=new JSONObject(true);origin.put("instanceId",instance);origin.put("taskId",lineage.taskId());origin.put("taskVersion",lineage.taskVersion());origin.put("tableName",lineage.outputTable());origin.put("modelId",input==null?null:input.getLong("id"));origin.put("readTables",new ArrayList<>(readModels.keySet()));origin.put("fileSha256",receipt.getString("fileSha256"));origin.put("fileRows",receipt.getLong("fileRows"));origin.put("csvColumns",sourceColumn.csvColumns());origin.put("conditionColumns",sourceColumn.conditionColumns());origin.put("steps",sourceColumn.steps());origin.put("targetType",targetType);origins.add(origin);
            for(String condition:sourceColumn.conditionColumns())conditions.add(Map.of("instanceId",instance,"tableName",lineage.outputTable(),"columnName",condition,"kind","CSV_CONDITION"));
        }
        field.put("inputOrigins",origins);field.put("valueSources",sources);field.put("conditionSources",conditions);
        field.put("rowSources",readModels.entrySet().stream().map(entry->Map.of("modelId",entry.getValue().getLongValue("id"),"tableName",entry.getKey(),"columnName","*","kind","ROWS")).toList());
        field.put("originKind","INPUT_SNAPSHOT");field.put("resolution","RESOLVED");field.remove("message");
    }
    private static void require(boolean valid,String message){if(!valid)throw new IllegalArgumentException(message);}
}
