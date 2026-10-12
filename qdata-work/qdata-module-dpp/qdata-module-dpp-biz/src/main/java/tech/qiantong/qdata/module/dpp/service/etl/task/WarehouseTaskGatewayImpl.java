package tech.qiantong.qdata.module.dpp.service.etl.task;

import cn.hutool.core.text.csv.CsvParser;
import cn.hutool.core.text.csv.CsvReadConfig;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.qiantong.qdata.common.config.AniviaConfig;
import tech.qiantong.qdata.common.database.core.HiveTableIdentity;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.dpp.api.warehouse.WarehouseTaskGateway;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNewNodeSaveReqVO;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskService;
import tech.qiantong.qdata.api.ds.api.service.etl.IDsEtlTaskService;
import tech.qiantong.qdata.quartz.scheduler.ISchedulerAdapter;
import tech.qiantong.qdata.quartz.domain.ScheduleCommand;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WarehouseTaskGatewayImpl implements WarehouseTaskGateway {
    private final org.springframework.beans.factory.ObjectProvider<IDppEtlTaskService> taskServices;
    private IDppEtlTaskService tasks(){return taskServices.getObject();}
    private final DppPublishedExecutionPlan published;
    private final JdbcTemplate jdbc;
    private final IDsEtlTaskService dolphin;
    private final ISchedulerAdapter schedulerAdapter;
    private final WarehouseNativeDolphinService nativeDolphin;

    @Override public InputLineage inputLineage(long instanceId) {
        var rows=jdbc.queryForList("SELECT i.task_id,i.task_code,i.task_version,i.status,t.project_id,t.project_code,t.type FROM dpp_etl_task_instance i JOIN dpp_etl_task t ON t.id=i.task_id WHERE i.id=? AND i.del_flag='0' AND t.del_flag='0'",instanceId);
        if(rows.size()!=1 || !"7".equals(String.valueOf(rows.get(0).get("status"))) || !"1".equals(String.valueOf(rows.get(0).get("type"))))throw new IllegalArgumentException("接入实例不是已成功的原始采集");
        var run=rows.get(0);long taskId=((Number)run.get("task_id")).longValue();int version=((Number)run.get("task_version")).intValue();
        var nodes=published.load(String.valueOf(run.get("task_code")),String.valueOf(run.get("project_code")),(long)version);Map<String,Set<String>> parents=new LinkedHashMap<>();
        jdbc.query("SELECT pre_node_code,post_node_code FROM dpp_etl_task_node_rel_log WHERE task_code=? AND project_code=? AND task_version=? AND del_flag='0'",row->{String pre=row.getString(1),post=row.getString(2);var set=parents.computeIfAbsent(post,unused->new LinkedHashSet<>());if(pre!=null&&!pre.isBlank()&&!"0".equals(pre))set.add(pre);},run.get("task_code"),run.get("project_code"),version);
        var flow=WarehouseInputLineage.build(nodes,parents);var writer=flow.writer();var connection=writer.getJSONObject("writerDatasource");
        if(connection==null || !"HIVE".equalsIgnoreCase(connection.getString("datasourceType")))throw new IllegalArgumentException("接入字段血缘目标不是Hive");
        Long source=writer.getLong("target_datasource_id");if(source==null)source=connection.getLong("datasourceId");
        String database=connection.getString("dbname");if(database==null&&connection.get("datasourceConfig")!=null)database=JSON.parseObject(connection.getString("datasourceConfig")).getString("dbname");
        return new InputLineage(instanceId,taskId,version,((Number)run.get("project_id")).longValue(),Objects.requireNonNull(source),HiveTableIdentity.resolve(writer.getString("target_table_name"),database).canonical(),flow.columns());
    }

    @Override public InputReceipt receipt(long instanceId) {
        var rows=jdbc.queryForList("SELECT i.task_id,i.task_code,i.task_version,i.status,t.project_id,t.project_code,t.type FROM dpp_etl_task_instance i JOIN dpp_etl_task t ON t.id=i.task_id WHERE i.id=? AND i.del_flag='0' AND t.del_flag='0'",instanceId);
        if(rows.size()!=1 || !"7".equals(String.valueOf(rows.get(0).get("status"))) || !"1".equals(String.valueOf(rows.get(0).get("type"))))
            throw new IllegalArgumentException("输入必须引用已成功的数据集成任务实例");
        var run=rows.get(0);long taskId=((Number)run.get("task_id")).longValue();int version=((Number)run.get("task_version")).intValue();
        var nodes=published.load(String.valueOf(run.get("task_code")),String.valueOf(run.get("project_code")),(long)version);
        List<JSONObject> readers=new ArrayList<>(),writers=new ArrayList<>();
        for(var node:nodes) {
            var params=JSON.parseObject(node.getParameters());
            String component=params.getString("componentType");
            if(component==null)component=node.getComponentType();
            if(component==null || !Set.of("4","39","31","91").contains(component))throw new IllegalArgumentException("ODS 输入不能包含业务加工节点，请在 DWD 层处理");
            if("39".equals(component) && !"data_date".equals(params.getString("fieldDerivationName")))throw new IllegalArgumentException("ODS 接入仅允许派生采集日期字段");
            if("31".equals(component)) {
                var rules=params.getJSONArray("tableFields");
                if(rules!=null)for(int i=0;i<rules.size();i++) {
                    var rule=rules.getJSONObject(i);var config=JSON.parseObject(rule.getString("ruleConfig"));
                    if(!"DATE_FORMAT_STD".equals(rule.getString("ruleType")) || config==null || !JSON.parseArray(config.getString("columns"),String.class).equals(List.of("data_date")))
                        throw new IllegalArgumentException("ODS 接入不能清洗原始观测字段");
                }
            }
            if("4".equals(component))readers.add(params);
            if("91".equals(component))writers.add(params);
        }
        if(readers.size()!=1 || writers.size()!=1)throw new IllegalArgumentException("本轮输入登记需要单个 CSV 到单个 Hive 输出的成功实例");
        requireRawReader(readers.get(0));
        var writer=writers.get(0);var connection=writer.getJSONObject("writerDatasource");
        if(!Objects.equals(writer.getJSONArray("columns"),writer.getJSONArray("target_columns")))throw new IllegalArgumentException("ODS 输入输出字段映射必须保持原始字段顺序");
        if(connection==null || !"HIVE".equalsIgnoreCase(connection.getString("datasourceType")) || writer.getIntValue("writeModeType")!=2)
            throw new IllegalArgumentException("输入必须是 Hive 追加采集结果");
        Long datasourceId=writer.getLong("target_datasource_id");if(datasourceId==null)datasourceId=connection.getLong("datasourceId");
        String defaultDatabase=connection.getString("dbname");
        if(defaultDatabase==null && connection.get("datasourceConfig")!=null)defaultDatabase=JSON.parseObject(connection.getString("datasourceConfig")).getString("dbname");
        String table=HiveTableIdentity.resolve(writer.getString("target_table_name"),defaultDatabase).canonical();
        String original=readers.get(0).getString("file");
        try {
            if(original==null || !original.startsWith("/profile/"))throw new IllegalArgumentException("CSV 原始文件位置缺失");
            Path root=Path.of(AniviaConfig.getProfile()).toRealPath();Path file=root.resolve(original.substring("/profile/".length())).normalize().toRealPath();
            if(!file.startsWith(root) || !Files.isRegularFile(file))throw new IllegalArgumentException("CSV 原始文件超出上传目录");
            long bytes=Files.size(file);if(bytes>256L*1024*1024)throw new IllegalArgumentException("CSV 超过本轮输入验证容量，请按批次拆分");
            String hash=sha256(file);long count=countCsv(file);
            if(!hash.equals(sha256(file)))throw new IllegalArgumentException("验证期间原始 CSV 已变化，请重新登记");
            return new InputReceipt(instanceId,taskId,version,((Number)run.get("project_id")).longValue(),Objects.requireNonNull(datasourceId),table,original,hash,bytes,count);
        } catch(IOException e){throw new IllegalArgumentException("原始 CSV 无法读取，不能创建输入快照",e);}
    }

    static void requireRawReader(JSONObject reader) {
        var columns=reader.getJSONArray("columns");
        if(columns==null || columns.isEmpty())throw new IllegalArgumentException("ODS CSV输入缺少原始字段映射");
        for(int i=0;i<columns.size();i++){
            var column=columns.getJSONObject(i);String type=column.getString("type");
            if(type==null || type.isBlank())type=column.getString("columnType");
            if(type!=null && !type.isBlank() && !"STRING".equalsIgnoreCase(type))throw new IllegalArgumentException("ODS CSV读取须保留原始文本，类型变换请在后续分层完成");
        }
    }
    static long countCsv(Path path) throws IOException {
        return tech.qiantong.qdata.common.utils.file.StrictCsv.read(Files.newInputStream(path),List.of(),null,1_000_000).rows();
    }
    static String sha256(Path file) throws IOException {
        try(InputStream stream=Files.newInputStream(file)) {
            var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];int n;
            while((n=stream.read(buffer))!=-1)digest.update(buffer,0,n);
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }

    @Override @Transactional public CreatedTask create(TaskSpec spec) {
        var categories=jdbc.queryForList("SELECT id,code FROM att_data_dev_cat WHERE project_id=? AND valid_flag='1' AND del_flag='0' ORDER BY sort_order,id LIMIT 1",spec.projectId());
        if(categories.isEmpty())throw new IllegalArgumentException("当前项目尚未配置数据开发类目，请先在项目基础管理中创建");
        var request=new DppEtlNewNodeSaveReqVO();request.setName(spec.name());request.setDescription("由数仓模型批次生成，节点绑定固定版本；请从数仓批次管理修改或重试。");
        request.setCatId(((Number)categories.get(0).get("id")).longValue());request.setCatCode(String.valueOf(categories.get(0).get("code")));
        request.setProjectId(spec.projectId());request.setProjectCode(Long.valueOf(spec.projectCode()));request.setType("3");request.setScheduler("QUARTZ");request.setActuator("JDBC");request.setExecutionType("SERIAL_WAIT");request.setReleaseState("0");request.setSchedulerState("0");request.setCrontab("0 0 0 1 1 ? 2099");request.setPersonCharge(String.valueOf(SecurityUtils.getUserId()));
        request.setDraftJson(JSON.toJSONString(Map.of("name",spec.name(),"scheduler","QUARTZ","triggerScheduler",spec.scheduler(),"computeEngine",spec.computeEngine(),"actuator","JDBC","type","3","executionType","SERIAL_WAIT","warehouseBatchId",spec.batchId(),"crontab",request.getCrontab())));
        var draft=tasks().createEtlTaskFront(request);request.setId(draft.getId());request.setCode(draft.getCode());request.setVersion(1);
        Map<Integer,String> codes=new LinkedHashMap<>();
        for(var node:spec.nodes())if(codes.put(node.ordinal(),String.valueOf(tasks().getNodeUniqueKey(request)))!=null)throw new IllegalArgumentException("运行节点序号重复");
        if(new HashSet<>(codes.values()).size()!=codes.size())throw new IllegalStateException("运行节点编码重复，未保存执行关系");
        List<Map<String,Object>> definitions=new ArrayList<>(),relations=new ArrayList<>(),locations=new ArrayList<>();
        for(var node:spec.nodes()) {
            Map<String,Object> params=new LinkedHashMap<>();params.put("taskType","SQL");params.put("componentType","51");params.put("sqlType","1");params.put("sql",node.sqlPreview());params.put("datasources",Map.of("datasourceId",node.datasourceId(),"databaseName",node.databaseName()));params.put("warehouseBatchId",spec.batchId());params.put("warehouseOrdinal",node.ordinal());params.put("warehouseModelId",node.modelId());params.put("localParams",List.of());params.put("preSql",List.of());params.put("postSql",List.of());params.put("failRetryTimes",0);params.put("failRetryInterval",0);params.put("delayTime",0);
            Map<String,Object> definition=new LinkedHashMap<>();definition.put("name",node.name());definition.put("code",codes.get(node.ordinal()));definition.put("version",1);definition.put("taskType","SQL");definition.put("componentType","51");definition.put("taskPriority","MEDIUM");definition.put("workerGroup","default");definition.put("taskParams",params);definition.put("failRetryTimes",0);definition.put("failRetryInterval",0);definition.put("delayTime",0);definitions.add(definition);
            List<Integer> parents=node.dependencies().isEmpty()?Collections.singletonList(null):node.dependencies();
            for(Integer parent:parents) {
                if(parent!=null && !codes.containsKey(parent))throw new IllegalArgumentException("运行依赖不完整");
                relations.add(Map.of("name","","preTaskCode",parent==null?0:codes.get(parent),"preTaskVersion",parent==null?0:1,"postTaskCode",codes.get(node.ordinal()),"postTaskVersion",1,"conditionType","NONE","conditionParams",Map.of()));
            }
            locations.add(Map.of("taskCode",codes.get(node.ordinal()),"x",80+(node.ordinal()%5)*260,"y",100+(node.ordinal()/5)*160));
        }
        request.setTaskDefinitionList(JSON.toJSONString(definitions));request.setTaskRelationJson(JSON.toJSONString(relations));request.setLocations(locations);
        var saved=tasks().createEtlTaskFrontPostposition(request);
        jdbc.update("UPDATE dpp_etl_task SET warehouse_batch_id=? WHERE id=?",spec.batchId(),saved.getId());
        if(published.load(saved.getCode(),spec.projectCode(),1L).size()!=spec.nodes().size())throw new IllegalStateException("开发任务发布快照不完整");
        return new CreatedTask(saved.getId(),1,Map.copyOf(codes));
    }

    @Override public void start(long taskId) {
        var task=tasks().getById(taskId);if(task==null)throw new IllegalArgumentException("开发任务不存在");
        var request=new DppEtlNewNodeSaveReqVO();request.setId(String.valueOf(taskId));request.setProjectId(task.getProjectId());request.setProjectCode(Long.valueOf(task.getProjectCode()));request.setReleaseState("1");
        tasks().updateReleaseJobTask(request);var result=tasks().startDppEtlTask(taskId);
        if(!Integer.valueOf(200).equals(result.get("code")))throw new IllegalStateException("批次开发任务未成功提交");
    }

    /** DS owns the native model DAG; each node calls the guarded one-step runner. */
    @Override public void startViaDolphin(long taskId,String batchId,String projectCode) {
        nativeDolphin.start(taskId,batchId,projectCode);
    }

    @Scheduled(fixedDelay=5000)
    public void reconcileDolphinBatches() {
        List<Map<String,Object>> waiting;
        try { waiting=jdbc.queryForList("SELECT batch_id,task_id,project_code,instance_id FROM dp_warehouse_ds_dispatch WHERE state='WAITING' ORDER BY created_at LIMIT 20"); }
        catch(Exception unavailable) { return; }
        for(var row:waiting) {
            String batchId=String.valueOf(row.get("batch_id"));
            long taskId=((Number)row.get("task_id")).longValue();
            String state;
            try {state=dolphin.instanceState(String.valueOf(row.get("project_code")),((Number)row.get("instance_id")).longValue());}
            catch(Exception unavailable) {continue;}
            if("SUCCESS".equals(state)) {
                if(jdbc.update("UPDATE dp_warehouse_ds_dispatch SET state='TRIGGERING' WHERE batch_id=? AND state='WAITING'",batchId)!=1)continue;
                try {
                    if(jdbc.update("UPDATE dp_warehouse_batch SET status='RUNNING',message='DolphinScheduler 实例成功，受管五层执行器已接管' WHERE id=? AND status='WAITING_DS'",batchId)!=1)
                        throw new IllegalStateException("数仓批次状态已变化");
                    var task=tasks().getById(taskId);
                    if(task==null || task.getQuartzId()==null || task.getQuartzId()<=0)throw new IllegalStateException("受管执行器尚未发布");
                    schedulerAdapter.trigger(ScheduleCommand.builder().id(task.getQuartzId()).build());
                    jdbc.update("UPDATE dp_warehouse_ds_dispatch SET state='FIRED' WHERE batch_id=? AND state='TRIGGERING'",batchId);
                } catch(Exception uncertain) {
                    jdbc.update("UPDATE dp_warehouse_ds_dispatch SET state='UNKNOWN',message='接管结果未知；禁止原地重试' WHERE batch_id=?",batchId);
                    jdbc.update("UPDATE dp_warehouse_batch SET status='UNKNOWN',message='DolphinScheduler 接管结果未知；输出保留，禁止原地重试' WHERE id=? AND status IN ('WAITING_DS','RUNNING')",batchId);
                }
            } else if(Set.of("FAILURE","STOP","KILL").contains(state)) {
                if(jdbc.update("UPDATE dp_warehouse_ds_dispatch SET state='FAILED',message=? WHERE batch_id=? AND state='WAITING'",state,batchId)==1) {
                    jdbc.update("UPDATE dp_warehouse_batch SET status='FAILED',finished_at=NOW(6),message='DolphinScheduler 触发失败，未执行Hive' WHERE id=? AND status='WAITING_DS'",batchId);
                    jdbc.update("UPDATE dp_warehouse_batch_step SET status='BLOCKED',message='调度触发失败，未执行Hive' WHERE batch_id=? AND status='QUEUED'",batchId);
                }
            }
        }
        jdbc.update("UPDATE dp_warehouse_batch b JOIN dp_warehouse_ds_dispatch d ON d.batch_id=b.id SET b.status='UNKNOWN',b.message='调度接管中断；禁止原地重试',d.state='UNKNOWN' WHERE d.state='TRIGGERING' AND d.updated_at<DATE_SUB(NOW(6),INTERVAL 2 MINUTE) AND b.status IN ('WAITING_DS','RUNNING')");
    }
}
