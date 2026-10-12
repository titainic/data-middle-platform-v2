package tech.qiantong.qdata.module.dp.warehouse;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.dpp.api.warehouse.WarehouseTaskGateway;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import static tech.qiantong.qdata.module.dp.warehouse.WarehouseSql.require;

/** Persistent preparation and provenance; computation is delegated to data development. */
@Service
@RequiredArgsConstructor
public class WarehouseBatchRegistry {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final WarehouseSpaceService spaces;
    private final WarehouseService models;
    private final WarehouseTaskGateway tasks;
    private final DataSourceFactory factory;
    @Value("${warehouse.ds.project-code:}") private String dolphinProjectCode;
    @Data public static class Input { private Long modelId; private Long instanceId; }
    @Data public static class Request {
        private Long spaceId;private String name;private LocalDate windowStart;private LocalDate windowEnd;
        private List<Long> modelIds=new ArrayList<>();private List<Input> inputs=new ArrayList<>();private String retryOf;
        private String scheduler="QUARTZ";
        private String computeEngine="HIVE_TEZ";
        private Long computeDatasourceId;
        private String computeEndpointFingerprint;
    }
    public record InputProof(long templateModelId,long datasetId,List<WarehouseDefinition> readModels,
                             List<WarehouseTaskGateway.InputReceipt> receipts,long rows,long windowRows,String sha256) { }

    public Map<String,Object> get(String id) {
        var row=internal(id);spaces.authorize(((Number)row.get("space_id")).longValue());
        var result=new LinkedHashMap<>(row);result.remove("lease_owner");
        Request request=JSON.parseObject(String.valueOf(row.get("request_json")),Request.class);
        result.put("scheduler",request.getScheduler());result.put("computeEngine",request.getComputeEngine());
        result.put("computeDatasourceId",request.getComputeDatasourceId());
        result.put("steps",jdbc.queryForList("SELECT ordinal,template_model_id,template_revision,model_id,dataset_id,node_code,status,row_count,output_fingerprint,started_at,finished_at,message FROM dp_warehouse_batch_step WHERE batch_id=? ORDER BY ordinal",id));
        return result;
    }
    Map<String,Object> internal(String id) {
        UUID.fromString(id);var rows=jdbc.queryForList("SELECT * FROM dp_warehouse_batch WHERE id=?",id);
        require(rows.size()==1,"数仓批次不存在");return rows.get(0);
    }
    public List<Map<String,Object>> list(long spaceId) {
        spaces.authorize(spaceId);
        var rows=jdbc.queryForList("SELECT id,name,attempt,retry_of,window_start,window_end,status,task_id,task_version,active_instance_id,created_at,started_at,finished_at,message,request_json FROM dp_warehouse_batch WHERE space_id=? ORDER BY created_at DESC LIMIT 100",spaceId);
        rows.forEach(row->{Request request=JSON.parseObject(String.valueOf(row.remove("request_json")),Request.class);row.put("scheduler",request.getScheduler());row.put("computeEngine",request.getComputeEngine());});
        return rows;
    }
    public List<Map<String,Object>> inputCandidates(long spaceId) {
        spaces.authorize(spaceId);var space=spaces.getInternal(spaceId);
        Map<String,WarehouseDefinition> available=models.list().stream().filter(m->Objects.equals(m.getSpaceId(),spaceId) && "ODS".equals(m.getLayer()))
                .collect(Collectors.toMap(m->WarehouseSql.canonical(m.getTableName(),m.getDatabaseName()),m->m));
        var runs=jdbc.queryForList("SELECT i.id,t.name,i.start_time,i.end_time FROM dpp_etl_task_instance i JOIN dpp_etl_task t ON t.id=i.task_id WHERE t.project_id=? AND t.type='1' AND i.status='7' AND i.del_flag='0' AND t.del_flag='0' ORDER BY i.id DESC LIMIT 30",space.getProjectId());
        List<Map<String,Object>> candidates=new ArrayList<>();
        for(var run:runs) {
            try {
                var receipt=tasks.receipt(((Number)run.get("id")).longValue());var model=available.get(receipt.outputTable());
                if(model==null || receipt.datasourceId()!=space.getDatasourceId())continue;
                Map<String,Object> row=new LinkedHashMap<>(run);row.put("modelId",model.getId());row.put("datasetId",model.getDatasetId());row.put("table",receipt.outputTable());row.put("file",receipt.originalFile());row.put("fileSha256",receipt.fileSha256());row.put("rows",receipt.fileRows());candidates.add(row);
            } catch(IllegalArgumentException ignored) { /* Only verified raw CSV receipts are selectable. */ }
        }
        return candidates;
    }

    public Map<String,Object> create(Request request) throws Exception {
        spaces.authorize(request.getSpaceId());var space=spaces.getInternal(request.getSpaceId());
        require(request.getName()!=null && !request.getName().isBlank() && request.getName().length()<=128,"请填写批次名称，最多128字");
        if(request.getScheduler()==null)request.setScheduler("QUARTZ");
        if(request.getComputeEngine()==null)request.setComputeEngine("HIVE_TEZ");
        require(Set.of("QUARTZ","DOLPHINSCHEDULER").contains(request.getScheduler()),"请选择 Quartz 或 DolphinScheduler 调度器");
        require(Set.of("HIVE_TEZ","KYUUBI_SPARK").contains(request.getComputeEngine()),"请选择 Hive/Tez 或 Kyuubi/Spark 计算引擎");
        if("DOLPHINSCHEDULER".equals(request.getScheduler()))
            require(dolphinProjectCode!=null && dolphinProjectCode.matches("[0-9]{10,20}"),"DolphinScheduler 数仓项目尚未配置");
        String id=UUID.randomUUID().toString();List<WarehouseDefinition> originals;List<Long> leaves;List<InputProof> inputs;String requestKey;
        if(request.getRetryOf()!=null && !request.getRetryOf().isBlank()) {
            var previous=get(request.getRetryOf());require(Objects.equals(((Number)previous.get("space_id")).longValue(),request.getSpaceId()),"重试必须属于原数仓空间");
            require(Set.of("FAILED","UNKNOWN").contains(String.valueOf(previous.get("status"))),"仅失败或状态未知的批次可以创建新尝试");
            Request saved=JSON.parseObject(String.valueOf(previous.get("request_json")),Request.class);
            request.setWindowStart(saved.getWindowStart());request.setWindowEnd(saved.getWindowEnd());request.setModelIds(saved.getModelIds());request.setInputs(saved.getInputs());
            request.setScheduler(saved.getScheduler());request.setComputeEngine(saved.getComputeEngine());request.setComputeDatasourceId(saved.getComputeDatasourceId());
            request.setComputeEndpointFingerprint(saved.getComputeEndpointFingerprint());
            if("KYUUBI_SPARK".equals(request.getComputeEngine())) {
                String fingerprint=tech.qiantong.qdata.common.database.core.HiveEndpointIdentity.fingerprint(spaces.computeProperty(space,request.getComputeEngine(),request.getComputeDatasourceId()));
                require(request.getComputeEndpointFingerprint()==null || request.getComputeEndpointFingerprint().equals(fingerprint),"Kyuubi 连接地址已变化，请重新建立批次");
                request.setComputeEndpointFingerprint(fingerprint);
            }
            var oldPlan=parsePlan(previous);originals=restoreTemplates(oldPlan);leaves=saved.getModelIds();inputs=parseInputs(previous);requestKey=String.valueOf(previous.get("request_key"));
            List<InputProof> retained=new ArrayList<>();
            for(var proof:inputs) {
                var completed=jdbc.queryForList("SELECT model_id FROM dp_warehouse_batch_step WHERE batch_id=? AND template_model_id=? AND status='SUCCESS'",request.getRetryOf(),proof.templateModelId());
                var sources=completed.isEmpty()?proof.readModels():List.of(models.getInternal(((Number)completed.get(0).get("model_id")).longValue()));
                retained.add(new InputProof(proof.templateModelId(),proof.datasetId(),sources,proof.receipts(),proof.rows(),proof.windowRows(),proof.sha256()));
            }
            inputs=retained;
        } else {
            require(request.getWindowStart()!=null && request.getWindowEnd()!=null && !request.getWindowStart().isAfter(request.getWindowEnd()),"请选择有效的业务日期窗口");
            leaves=request.getModelIds().stream().distinct().sorted().toList();require(!leaves.isEmpty(),"请选择下游交付模型");request.setModelIds(leaves);
            originals=models.list().stream().filter(m->Objects.equals(m.getSpaceId(),request.getSpaceId())).toList();
            var preview=WarehouseModelCompiler.compile(originals,leaves,id);inputs=verifyInputs(request,preview,space);
            if("KYUUBI_SPARK".equals(request.getComputeEngine()))validateSparkInputs(space,request,inputs);
            requestKey=canonicalKey(space.getId(),request,preview,inputs);
        }
        var plan=WarehouseModelCompiler.compile(originals,leaves,id);
        String user=SecurityUtils.getUsername();long userId=SecurityUtils.getUserId();String key=requestKey;List<InputProof> fixedInputs=List.copyOf(inputs);
        String selected=transaction.execute(status->{
            jdbc.queryForObject("SELECT id FROM dp_warehouse_space WHERE id=? FOR UPDATE",Long.class,space.getId());
            backfillKeys(space.getId());
            String canonical=canonicalKey(space.getId(),request,plan,fixedInputs);
            var existing=jdbc.queryForList("SELECT id,attempt,status FROM dp_warehouse_batch WHERE space_id=? AND canonical_key=? ORDER BY CASE WHEN status='SUCCESS' THEN 0 ELSE 1 END,attempt DESC,created_at DESC LIMIT 1",space.getId(),canonical);
            if((request.getRetryOf()==null || request.getRetryOf().isBlank()) && !existing.isEmpty()) {
                String prior=String.valueOf(existing.get(0).get("id"));submission(space.getId(),prior,key,"REUSED",request,user);return prior;
            }
            int attempt=jdbc.queryForObject("SELECT COALESCE(MAX(attempt),0)+1 FROM dp_warehouse_batch WHERE space_id=? AND canonical_key=?",Integer.class,space.getId(),canonical);
            jdbc.update("INSERT INTO dp_warehouse_batch(id,space_id,name,request_key,canonical_key,attempt,retry_of,window_start,window_end,status,request_json,plan_json,input_json,create_by,creator_id) VALUES (?,?,?,?,?,?,?,?,?,'PREPARING',?,?,?,?,?)",id,space.getId(),request.getName(),key,canonical,attempt,request.getRetryOf(),request.getWindowStart(),request.getWindowEnd(),JSON.toJSONString(request),JSON.toJSONString(plan),JSON.toJSONString(fixedInputs),user,userId);
            submission(space.getId(),id,key,"CREATED",request,user);return id;
        });
        if(!id.equals(selected))return get(selected);
        if(inputs.stream().mapToLong(InputProof::windowRows).sum()==0) {
            jdbc.update("UPDATE dp_warehouse_batch SET status='NO_DATA',finished_at=NOW(6),message='业务窗口内没有有效观测，本批次未写入Hive' WHERE id=?",id);return get(id);
        }
        try {
            registerSteps(id,request.getName(),plan,space,user,userId,request);
            jdbc.update("UPDATE dp_warehouse_batch SET status='READY',message='输入与模型版本已冻结，开发任务已生成' WHERE id=?",id);
        } catch(Exception error) {
            jdbc.update("UPDATE dp_warehouse_batch SET status='FAILED',finished_at=NOW(6),message=? WHERE id=?",error.getMessage(),id);throw error;
        }
        return get(id);
    }

    private List<InputProof> verifyInputs(Request request,List<WarehouseModelCompiler.Step> plan,WarehouseSpaceService.Space space) throws Exception {
        require(request.getInputs()!=null && !request.getInputs().isEmpty(),"请选择成功的原始数据接入实例");
        Map<Long,WarehouseDefinition> required=new LinkedHashMap<>();for(var step:plan)if("ODS".equals(step.definition().getLayer()))required.put(step.definition().getDatasetId(),step.definition());
        Map<Long,LinkedHashMap<String,Map.Entry<WarehouseDefinition,WarehouseTaskGateway.InputReceipt>>> grouped=new LinkedHashMap<>();
        for(var input:request.getInputs()) {
            require(input.getModelId()!=null && input.getInstanceId()!=null,"输入模型和接入实例不能为空");var model=models.get(input.getModelId());
            require("ODS".equals(model.getLayer()) && Objects.equals(model.getSpaceId(),space.getId()) && required.containsKey(model.getDatasetId()),"输入必须属于本空间所需的原始数据集");
            var receipt=tasks.receipt(input.getInstanceId());
            require(receipt.projectId()==space.getProjectId() && receipt.datasourceId()==space.getDatasourceId() && receipt.outputTable().equals(WarehouseSql.canonical(model.getTableName(),model.getDatabaseName())),"接入实例与原始模型库表不匹配");
            require(schema(model).equals(schema(required.get(model.getDatasetId()))),"原始输入结构与数据集模板不一致");
            grouped.computeIfAbsent(model.getDatasetId(),unused->new LinkedHashMap<>()).putIfAbsent(receipt.fileSha256(),Map.entry(model,receipt));
        }
        require(grouped.keySet().equals(required.keySet()),"每个原始数据集都必须选择输入，不能遗漏或混入其他数据集");
        var query=factory.createDbQuery(spaces.property(space));List<InputProof> result=new ArrayList<>();
        try(Connection connection=query.getConnection();Statement statement=connection.createStatement()) {
            statement.setQueryTimeout(300);
            for(var entry:grouped.entrySet()) {
                var template=required.get(entry.getKey());var columns=WarehouseSql.ordered(template);List<WarehouseDefinition> sources=new ArrayList<>();List<WarehouseTaskGateway.InputReceipt> receipts=new ArrayList<>();long windowRows=0;
                for(var source:entry.getValue().values()) {
                    var model=source.getKey();String table=WarehouseSql.qualified(model.getDatabaseName(),model.getTableName());models.verifySchema(statement,model,table);
                    var fingerprint=WarehouseDataFingerprint.read(connection,List.of(WarehouseSql.canonical(model.getTableName(),model.getDatabaseName())),columns,null,null,1_000_000);
                    require(fingerprint.rows()==source.getValue().fileRows(),"原始库表行数与CSV不符；请使用独立的输入版本，不能重复追加同一文件");
                    require(columns.stream().anyMatch(c->c.getName().equals("event_time")) && columns.stream().anyMatch(c->c.getName().equals("device_id")),"当前IoT输入需声明device_id、event_time与data_date字段");
                    try(ResultSet invalid=statement.executeQuery("SELECT COUNT(*) FROM "+table+" WHERE device_id IS NULL OR TRIM(device_id)='' OR event_time IS NULL OR data_date IS NULL OR CAST(data_date AS DATE) IS NULL OR data_date<>SUBSTR(CAST(event_time AS STRING),1,10)")){invalid.next();require(invalid.getLong(1)==0,"原始输入存在缺失业务键或无效采集日期，请先核对接入质量");}
                    try(ResultSet count=statement.executeQuery("SELECT COUNT(*) FROM "+table+WarehouseDataFingerprint.window(columns,request.getWindowStart(),request.getWindowEnd()))){count.next();windowRows+=count.getLong(1);}
                    sources.add(model);receipts.add(source.getValue());
                }
                var fingerprint=WarehouseDataFingerprint.read(connection,sources.stream().map(m->WarehouseSql.canonical(m.getTableName(),m.getDatabaseName())).toList(),columns,null,null,1_000_000);
                long templateId=plan.stream().filter(s->s.definition().getDatasetId().equals(entry.getKey()) && "ODS".equals(s.definition().getLayer())).findFirst().orElseThrow().templateModelId();
                result.add(new InputProof(templateId,entry.getKey(),List.copyOf(sources),List.copyOf(receipts),fingerprint.rows(),windowRows,fingerprint.sha256()));
            }
        } finally {query.close();}
        return result;
    }
    private static String schema(WarehouseDefinition model) {return JSON.toJSONString(WarehouseSql.ordered(model).stream().map(c->List.of(c.getName(),WarehouseSql.type(c.getType()),c.isPartition())).toList());}

    private void validateSparkInputs(WarehouseSpaceService.Space space,Request request,List<InputProof> inputs) throws Exception {
        var property=spaces.computeProperty(space,request.getComputeEngine(),request.getComputeDatasourceId());
        request.setComputeEndpointFingerprint(tech.qiantong.qdata.common.database.core.HiveEndpointIdentity.fingerprint(property));
        var query=factory.createDbQuery(property);
        try(Connection connection=query.getConnection()) {
            for(var proof:inputs) {
                var readModels=proof.readModels();
                var columns=WarehouseSql.ordered(readModels.get(0));
                var tables=readModels.stream().map(m->WarehouseSql.canonical(m.getTableName(),m.getDatabaseName())).toList();
                var value=WarehouseDataFingerprint.read(connection,tables,columns,null,null,1_000_000);
                require(value.rows()==proof.rows() && value.sha256().equals(proof.sha256()),"Kyuubi 与 HiveServer2 的 ODS 原始输入不一致，禁止跨元数据源计算");
            }
        } finally {query.close();}
    }

    tech.qiantong.qdata.common.database.constants.DbQueryProperty computeProperty(Map<String,Object> batch,WarehouseDefinition definition) {
        Request request=JSON.parseObject(String.valueOf(batch.get("request_json")),Request.class);
        if(!"KYUUBI_SPARK".equals(request.getComputeEngine()))return models.property(definition);
        var space=spaces.getInternal(((Number)batch.get("space_id")).longValue());
        var property=spaces.computeProperty(space,request.getComputeEngine(),request.getComputeDatasourceId());
        property.setDbName(definition.getDatabaseName());
        return property;
    }

    private void registerSteps(String batchId,String name,List<WarehouseModelCompiler.Step> plan,WarehouseSpaceService.Space space,String user,long userId,Request request) {
        transaction.executeWithoutResult(status->{
        Map<Long,Long> ids=new LinkedHashMap<>();Map<Long,Integer> positions=new LinkedHashMap<>();List<WarehouseTaskGateway.NodeSpec> nodes=new ArrayList<>();
        for(int i=0;i<plan.size();i++) {
            var step=plan.get(i);var definition=JSON.parseObject(JSON.toJSONString(step.definition()),WarehouseDefinition.class);
            if("DWD".equals(definition.getLayer())) {
                var source=plan.stream().filter(p->definition.getUpstreamIds().contains(p.templateModelId())).map(WarehouseModelCompiler.Step::definition).toList();
                WarehouseModelCompiler.deduplicateInputs(definition,source);
                WarehouseModelCompiler.applyWindow(definition,request.getWindowStart(),request.getWindowEnd());
            }
            definition.setName(definition.getName()+" / "+name);definition.setDescription("固定模板版本 "+step.templateRevision()+"；运行批次 "+batchId+"；历史对象保留。");
            definition.setUpstreamIds(definition.getUpstreamIds().stream().map(ids::get).toList());definition.getDimensions().forEach(r->r.setModelId(ids.get(r.getModelId())));
            var saved=models.save(definition,user,userId);ids.put(step.templateModelId(),saved.getId());positions.put(step.templateModelId(),i);
            List<Integer> dependencies=step.dependencies().stream().map(positions::get).toList();require(dependencies.stream().noneMatch(Objects::isNull),"任务依赖未注册完成");
            jdbc.update("INSERT INTO dp_warehouse_batch_step(batch_id,ordinal,template_model_id,template_revision,model_id,dataset_id,definition_json,dependencies_json,status) VALUES (?,?,?,?,?,?,?,?,'QUEUED')",batchId,i,step.templateModelId(),step.templateRevision(),saved.getId(),saved.getDatasetId(),JSON.toJSONString(saved),JSON.toJSONString(dependencies));
            String sql=WarehouseSql.ddl(saved,saved.getDatabaseName())+";\n"+("ODS".equals(saved.getLayer())?"-- 原始数据由固定输入清单保存为完整版本快照":WarehouseSql.insert(saved,saved.getDatabaseName()));
            nodes.add(new WarehouseTaskGateway.NodeSpec(i,saved.getId(),saved.getName(),sql,
                    request.getComputeDatasourceId()==null?saved.getDatasourceId():request.getComputeDatasourceId(),saved.getDatabaseName(),dependencies));
        }
        var task=tasks.create(new WarehouseTaskGateway.TaskSpec(batchId,name,space.getProjectId(),space.getProjectCode(),request.getScheduler(),request.getComputeEngine(),nodes));
            jdbc.update("UPDATE dp_warehouse_batch SET task_id=?,task_version=? WHERE id=?",task.taskId(),task.version(),batchId);
            task.nodeCodes().forEach((ordinal,code)->jdbc.update("UPDATE dp_warehouse_batch_step SET node_code=? WHERE batch_id=? AND ordinal=?",code,batchId,ordinal));
        });
    }
    private void submission(long space,String batch,String key,String outcome,Request request,String user) {
        jdbc.update("INSERT INTO dp_warehouse_batch_submission(space_id,batch_id,request_key,outcome,source_json,create_by) VALUES (?,?,?,?,?,?)",space,batch,key,outcome,JSON.toJSONString(request.getInputs()),user);
    }
    public Map<String,Object> start(String id) {
        var batch=get(id);if(Set.of("RUNNING","SUCCESS","NO_DATA").contains(String.valueOf(batch.get("status"))))return batch;
        require("READY".equals(batch.get("status")) && batch.get("task_id")!=null,"批次未就绪；失败或未知状态请创建新尝试");
        Request request=JSON.parseObject(String.valueOf(batch.get("request_json")),Request.class);
        boolean ds="DOLPHINSCHEDULER".equals(request.getScheduler());
        int changed=jdbc.update("UPDATE dp_warehouse_batch SET status=?,started_at=NOW(6),message=? WHERE id=? AND status='READY'",ds?"WAITING_DS":"RUNNING",ds?"DolphinScheduler 触发中":"开发任务已提交",id);
        if(changed==1)try{
            if(ds)tasks.startViaDolphin(((Number)batch.get("task_id")).longValue(),id,dolphinProjectCode);
            else tasks.start(((Number)batch.get("task_id")).longValue());
        }catch(Exception error){jdbc.update("UPDATE dp_warehouse_batch SET status='UNKNOWN',message=? WHERE id=? AND status IN ('WAITING_DS','RUNNING')",error.getMessage(),id);throw error;}
        return get(id);
    }
    static List<InputProof> parseInputs(Map<String,Object> row) {return JSON.parseObject(String.valueOf(row.get("input_json")),new TypeReference<List<InputProof>>(){});}
    static List<WarehouseModelCompiler.Step> parsePlan(Map<String,Object> row) {return JSON.parseObject(String.valueOf(row.get("plan_json")),new TypeReference<List<WarehouseModelCompiler.Step>>(){});}
    static String canonicalKey(long spaceId,Request request,List<WarehouseModelCompiler.Step> plan,List<InputProof> inputs) {
        var versions=plan.stream().sorted(Comparator.comparingLong(WarehouseModelCompiler.Step::templateModelId)).map(s->List.of(s.templateModelId(),(long)s.templateRevision(),s.definition().getDatasetId())).toList();
        var data=inputs.stream().sorted(Comparator.comparingLong(InputProof::datasetId)).map(p->List.of(p.datasetId(),p.rows(),p.sha256(),p.receipts().stream().map(WarehouseTaskGateway.InputReceipt::fileSha256).sorted().toList())).toList();
        var key=new ArrayList<Object>(List.of(spaceId,List.of(request.getWindowStart().toString(),request.getWindowEnd().toString()),request.getModelIds().stream().sorted().toList(),versions,data));
        if(!"QUARTZ".equals(request.getScheduler()) || !"HIVE_TEZ".equals(request.getComputeEngine()) || request.getComputeDatasourceId()!=null)
            key.add(List.of(request.getScheduler(),request.getComputeEngine(),Objects.toString(request.getComputeDatasourceId(),"")));
        return WarehouseDataFingerprint.sha(JSON.toJSONString(key));
    }
    private void backfillKeys(long spaceId) {
        for(var row:jdbc.queryForList("SELECT * FROM dp_warehouse_batch WHERE space_id=? AND canonical_key IS NULL",spaceId)) {
            var request=JSON.parseObject(String.valueOf(row.get("request_json")),Request.class);
            String key=canonicalKey(spaceId,request,parsePlan(row),parseInputs(row));
            jdbc.update("UPDATE dp_warehouse_batch SET canonical_key=? WHERE id=? AND canonical_key IS NULL",key,row.get("id"));
        }
    }
    private static List<WarehouseDefinition> restoreTemplates(List<WarehouseModelCompiler.Step> plan) {
        Map<String,String> reverse=plan.stream().collect(Collectors.toMap(WarehouseModelCompiler.Step::targetTable,WarehouseModelCompiler.Step::sourceTable));
        return plan.stream().map(step->{var d=JSON.parseObject(JSON.toJSONString(step.definition()),WarehouseDefinition.class);d.setId(step.templateModelId());d.setRevision(step.templateRevision());d.setTableName(step.sourceTable().substring(step.sourceTable().indexOf('.')+1));if(!"ODS".equals(d.getLayer()))d.setSelectSql(WarehouseModelCompiler.rewriteTables(d.getSelectSql(),reverse));return d;}).toList();
    }
}
