package tech.qiantong.qdata.module.dp.warehouse;

import com.alibaba.fastjson2.JSON;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.dpp.api.warehouse.WarehouseQualityGateway;
import java.sql.Connection;
import java.util.*;
import static tech.qiantong.qdata.module.dp.warehouse.WarehouseSql.require;

/** Versioned quality policies and fixed assessments; only accepted evidence can pass a release gate. */
@Service
@RequiredArgsConstructor
public class WarehouseQualityService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final WarehouseSpaceService spaces;
    private final WarehouseBatchRegistry batches;
    private final WarehouseService models;
    private final WarehouseCatalogService catalog;
    private final WarehouseQualityGateway gateway;
    private final DataSourceFactory factory;
    private final tech.qiantong.qdata.redis.service.IRedisService redis;

    @Data public static class Binding { private Long datasetId;private Long taskId;private List<Long> blockingRuleIds=new ArrayList<>(); }
    @Data public static class PolicyRequest { private Long spaceId;private String name;private List<Binding> bindings=new ArrayList<>(); }

    public List<Map<String,Object>> policies(long spaceId) {
        spaces.authorize(spaceId);
        return jdbc.queryForList("SELECT id,name,version,definition_json,created_at FROM dp_warehouse_quality_policy WHERE space_id=? ORDER BY id DESC",spaceId);
    }
    public List<Map<String,Object>> templates(long spaceId) {
        spaces.authorize(spaceId);
        var candidates=jdbc.queryForList("SELECT DISTINCT t.id,t.task_name,m.dataset_id,m.database_name,m.table_name FROM dpp_quality_task t JOIN da_asset a ON a.id=t.asset_id JOIN dp_warehouse_materialization m ON m.dataset_id=a.dataset_id AND m.database_name=a.database_name AND m.table_name=a.table_name JOIN dp_warehouse_dataset d ON d.id=m.dataset_id WHERE d.space_id=? AND t.del_flag='0' AND a.del_flag='0' AND NOT EXISTS(SELECT 1 FROM dp_warehouse_quality_assessment_task r WHERE r.task_id=t.id) ORDER BY t.id",spaceId);
        for(var candidate:candidates) candidate.put("rules",gateway.template(((Number)candidate.get("id")).longValue()).get("rules"));
        return candidates;
    }
    public Map<String,Object> createPolicy(PolicyRequest request) {
        spaces.authorize(request.getSpaceId());
        require(request.getName()!=null && !request.getName().isBlank() && request.getName().length()<=128,"请填写质量策略名称，最多128字");
        require(request.getBindings()!=null && !request.getBindings().isEmpty(),"至少绑定一个数据集质量模板");
        Set<Long> datasets=new HashSet<>();List<Map<String,Object>> definitions=new ArrayList<>();
        var allowed=templates(request.getSpaceId());
        for(var binding:request.getBindings()) {
            require(binding.getDatasetId()!=null && datasets.add(binding.getDatasetId()),"质量数据集绑定不能为空或重复");
            require(allowed.stream().anyMatch(row->Objects.equals(((Number)row.get("id")).longValue(),binding.getTaskId()) && Objects.equals(((Number)row.get("dataset_id")).longValue(),binding.getDatasetId())),"质量模板不属于所选空间数据集");
            var template=gateway.template(binding.getTaskId());
            var rules=JSON.parseArray(JSON.toJSONString(template.get("rules")));
            Set<Long> ids=new HashSet<>();rules.forEach(value->ids.add(JSON.parseObject(JSON.toJSONString(value)).getLong("id")));
            require(binding.getBlockingRuleIds()!=null && !binding.getBlockingRuleIds().isEmpty() && ids.containsAll(binding.getBlockingRuleIds()),"请为每个数据集指定有效的关键阻断规则");
            Map<String,Object> definition=new LinkedHashMap<>();definition.put("datasetId",binding.getDatasetId());definition.put("template",template);definition.put("blockingRuleIds",binding.getBlockingRuleIds().stream().distinct().sorted().toList());definitions.add(definition);
        }
        definitions.sort(Comparator.comparing(row->((Number)row.get("datasetId")).longValue()));
        return transaction.execute(status->{
            jdbc.queryForObject("SELECT id FROM dp_warehouse_space WHERE id=? FOR UPDATE",Long.class,request.getSpaceId());
            int version=jdbc.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM dp_warehouse_quality_policy WHERE space_id=? AND name=?",Integer.class,request.getSpaceId(),request.getName());
            jdbc.update("INSERT INTO dp_warehouse_quality_policy(space_id,name,version,definition_json,creator_id) VALUES(?,?,?,?,?)",request.getSpaceId(),request.getName(),version,JSON.toJSONString(definitions),SecurityUtils.getUserId());
            return jdbc.queryForMap("SELECT * FROM dp_warehouse_quality_policy WHERE space_id=? AND name=? AND version=?",request.getSpaceId(),request.getName(),version);
        });
    }
    public Map<String,Object> createAssessment(String batchId,long policyId,boolean newAttempt) {
        var batch=batches.get(batchId);require("SUCCESS".equals(batch.get("status")),"仅计算完成的批次可以评估");
        var policy=jdbc.queryForMap("SELECT * FROM dp_warehouse_quality_policy WHERE id=?",policyId);
        require(Objects.equals(policy.get("space_id"),batch.get("space_id")),"质量策略与批次必须属于同一空间");
        var definitions=JSON.parseArray(String.valueOf(policy.get("definition_json")));
        var directory=catalog.catalog(batchId);Map<Long,Map<String,Object>> byDataset=new HashMap<>();directory.forEach(row->byDataset.put(((Number)row.get("datasetId")).longValue(),row));
        List<Map<String,Object>> inputs=new ArrayList<>();
        for(Object value:definitions) {
            var definition=JSON.parseObject(JSON.toJSONString(value));long datasetId=definition.getLongValue("datasetId");var item=byDataset.get(datasetId);
            require(item!=null && Boolean.TRUE.equals(item.get("linked")),"请先完成策略数据集的元数据与资产关联");
            long modelId=((Number)item.get("modelId")).longValue();var model=models.getInternal(modelId);
            Set<String> columns=new HashSet<>();model.getColumns().forEach(column->columns.add(column.getName().toLowerCase(Locale.ROOT)));
            for(Object ruleValue:definition.getJSONObject("template").getJSONArray("rules")) {
                String fields=JSON.parseObject(JSON.toJSONString(ruleValue)).getString("evaColumn");
                if(fields!=null && !fields.isBlank() && !"*".equals(fields))for(String field:fields.split(","))
                    require(columns.contains(field.trim().replace("`","").toLowerCase(Locale.ROOT)),"质量规则引用了当前数据版本不存在的字段："+field);
            }
            var step=jdbc.queryForMap("SELECT row_count,output_fingerprint FROM dp_warehouse_batch_step WHERE batch_id=? AND model_id=? AND status='SUCCESS'",batchId,modelId);
            var fingerprint=fingerprint(model);
            require(fingerprint.sha256().equals(step.get("output_fingerprint")),"批次数据已变化，不能沿用旧计算结果评估");
            var input=new LinkedHashMap<String,Object>(item);input.put("definition",definition);input.put("fingerprint",fingerprint.sha256());input.put("rows",fingerprint.rows());inputs.add(input);
        }
        return transaction.execute(status->{
            jdbc.queryForObject("SELECT id FROM dp_warehouse_space WHERE id=? FOR UPDATE",Long.class,((Number)batch.get("space_id")).longValue());
            var previous=jdbc.queryForList("SELECT * FROM dp_warehouse_quality_assessment WHERE batch_id=? AND policy_id=? ORDER BY attempt DESC LIMIT 1",batchId,policyId);
            if(!previous.isEmpty() && !newAttempt)return get(String.valueOf(previous.get(0).get("id")));
            if(!previous.isEmpty())require(!Set.of("READY","RUNNING").contains(String.valueOf(previous.get(0).get("status"))),"前一次评估尚未结束");
            int attempt=previous.isEmpty()?1:((Number)previous.get(0).get("attempt")).intValue()+1;String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO dp_warehouse_quality_assessment(id,batch_id,policy_id,attempt,status,creator_id,message) VALUES(?,?,?,?,'READY',?,?)",id,batchId,policyId,attempt,SecurityUtils.getUserId(),"规则、检查对象与输入摘要已固定");
            for(var input:inputs) {
                var definition=JSON.parseObject(JSON.toJSONString(input.get("definition")));
                var template=definition.getJSONObject("template");long modelId=((Number)input.get("modelId")).longValue();var model=models.getInternal(modelId);
                var prepared=gateway.create(policy.get("name")+" v"+policy.get("version")+" / 数据集"+input.get("datasetId")+" / 尝试"+attempt,model.getDatasourceId(),String.valueOf(input.get("table")),((Number)input.get("assetId")).longValue(),template);
                var blocking=definition.getJSONArray("blockingRuleIds").toJavaList(Long.class).stream().map(prepared.ruleIds()::get).toList();
                require(blocking.stream().allMatch(Objects::nonNull),"关键规则映射缺失");
                jdbc.update("INSERT INTO dp_warehouse_quality_assessment_task(assessment_id,dataset_id,model_id,asset_id,task_id,planned_json,blocking_rule_ids,input_fingerprint,row_count,status) VALUES(?,?,?,?,?,?,?,?,?,'READY')",id,input.get("datasetId"),modelId,input.get("assetId"),prepared.taskId(),prepared.plannedJson(),JSON.toJSONString(blocking),input.get("fingerprint"),input.get("rows"));
            }
            return get(id);
        });
    }
    public List<Map<String,Object>> assessments(String batchId) {
        batches.get(batchId);
        var rows=jdbc.queryForList("SELECT q.*,p.name policy_name,p.version policy_version FROM dp_warehouse_quality_assessment q JOIN dp_warehouse_quality_policy p ON p.id=q.policy_id WHERE q.batch_id=? ORDER BY q.created_at DESC",batchId);
        for(var row:rows)if("RUNNING".equals(row.get("status")))reconcile(String.valueOf(row.get("id")));
        return jdbc.queryForList("SELECT q.*,p.name policy_name,p.version policy_version FROM dp_warehouse_quality_assessment q JOIN dp_warehouse_quality_policy p ON p.id=q.policy_id WHERE q.batch_id=? ORDER BY q.created_at DESC",batchId);
    }
    public Map<String,Object> get(String id) {
        var row=jdbc.queryForMap("SELECT * FROM dp_warehouse_quality_assessment WHERE id=?",id);batches.get(String.valueOf(row.get("batch_id")));
        var result=new LinkedHashMap<>(row);result.put("tasks",jdbc.queryForList("SELECT * FROM dp_warehouse_quality_assessment_task WHERE assessment_id=? ORDER BY dataset_id",id));return result;
    }
    public Map<String,Object> start(String id) {
        var assessment=get(id);
        if(jdbc.update("UPDATE dp_warehouse_quality_assessment SET status='RUNNING',message=? WHERE id=? AND status='READY'","已提交原生质量任务，请查看实际评估结果",id)==0)return get(id);
        try {
            for(var task:jdbc.queryForList("SELECT task_id FROM dp_warehouse_quality_assessment_task WHERE assessment_id=? ORDER BY dataset_id",id))gateway.start(((Number)task.get("task_id")).longValue());
        } catch(RuntimeException failure) {
            jdbc.update("UPDATE dp_warehouse_quality_assessment SET status='FAILED',finished_at=NOW(6),message=? WHERE id=?",failure.getMessage(),id);
            throw failure;
        }
        return get(id);
    }
    private void reconcile(String id) {
        var tasks=jdbc.queryForList("SELECT t.*,l.success_flag,s.definition_json,TIMESTAMPDIFF(SECOND,a.created_at,NOW()) age_seconds FROM dp_warehouse_quality_assessment_task t JOIN dp_warehouse_quality_assessment a ON a.id=t.assessment_id LEFT JOIN dpp_quality_log l ON l.id=t.log_id LEFT JOIN dpp_quality_execution_snapshot s ON s.log_id=t.log_id WHERE t.assessment_id=? ORDER BY t.dataset_id",id);
        boolean finished=true,passed=true;
        for(var task:tasks) {
            if(task.get("log_id")==null || "2".equals(task.get("success_flag"))) {
                if(((Number)task.get("age_seconds")).longValue()>300 && !redis.hasKey("executeQualityTask-"+task.get("task_id")+":lease")) {
                    jdbc.update("UPDATE dp_warehouse_quality_assessment_task SET status='UNKNOWN',message=? WHERE task_id=?","执行持有者已失联，不能确认评估结果",task.get("task_id"));
                    if(task.get("log_id")!=null)jdbc.update("UPDATE dpp_quality_log SET success_flag='1',end_time=? WHERE id=? AND success_flag='2'",java.sql.Timestamp.from(java.time.Instant.now()),task.get("log_id"));
                    jdbc.update("UPDATE dp_warehouse_quality_assessment SET status='UNKNOWN',finished_at=NOW(6),message=? WHERE id=? AND status='RUNNING'","部分评估未取得可靠完成证据，请创建新尝试",id);
                }
                finished=false;continue;
            }
            if(!"0".equals(task.get("success_flag")) || task.get("definition_json")==null || !JSON.parseObject(String.valueOf(task.get("planned_json"))).equals(JSON.parseObject(String.valueOf(task.get("definition_json"))))) {
                passed=false;jdbc.update("UPDATE dp_warehouse_quality_assessment_task SET status='FAILED',message=? WHERE task_id=?","执行失败或快照不匹配，不能通过门禁",task.get("task_id"));continue;
            }
            var results=jdbc.queryForList("SELECT evaluate_id,total,problem_total FROM dpp_evaluate_log WHERE task_log_id=? AND del_flag='0'",String.valueOf(task.get("log_id")));
            Set<Long> blocking=new HashSet<>(JSON.parseArray(String.valueOf(task.get("blocking_rule_ids")),Long.class));
            var planned=JSON.parseObject(String.valueOf(task.get("planned_json"))).getJSONArray("rules");
            Set<Long> expected=new HashSet<>();planned.forEach(rule->expected.add(JSON.parseObject(JSON.toJSONString(rule)).getLong("id")));
            var verdict=WarehouseQualityVerdict.evaluate(results,expected,blocking,((Number)task.get("row_count")).longValue());
            long allHits=verdict.ruleHits(),blockingHits=verdict.blockingHits();boolean valid=verdict.passed();
            try { valid=valid && fingerprint(models.getInternal(((Number)task.get("model_id")).longValue())).sha256().equals(task.get("input_fingerprint")); }
            catch(RuntimeException changed) { valid=false; }
            passed &= valid;
            jdbc.update("UPDATE dp_warehouse_quality_assessment_task SET status=?,rule_hits=?,blocking_hits=?,message=? WHERE task_id=?",valid?"PASSED":"FAILED",allHits,blockingHits,valid?"关键规则通过；问题状态操作不会改变此执行证据":"关键规则失败、检查缺失或数据变化",task.get("task_id"));
        }
        if(finished) {
            jdbc.update("UPDATE dp_warehouse_quality_assessment SET status=?,finished_at=NOW(6),message=? WHERE id=? AND status='RUNNING'",passed?"PASSED":"FAILED",passed?"全部关键规则通过，具备后续服务发布的质量条件":"质量门禁未通过，不能发布本次结果",id);
            for(var task:tasks)gateway.pause(((Number)task.get("task_id")).longValue());
        }
    }
    private WarehouseDataFingerprint.Value fingerprint(WarehouseDefinition model) {
        var query=factory.createDbQuery(models.property(model));
        try(Connection connection=query.getConnection()) {
            return WarehouseDataFingerprint.read(connection,List.of(WarehouseSql.canonical(model.getTableName(),model.getDatabaseName())),WarehouseSql.ordered(model),null,null,1_000_000);
        } catch(Exception failure) { throw new IllegalArgumentException("质量输入摘要校验失败",failure); }
        finally { query.close(); }
    }
}
