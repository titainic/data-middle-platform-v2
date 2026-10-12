package tech.qiantong.qdata.quality.service.quality;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.quality.controller.qa.vo.DppQualityTaskRespVO;
import tech.qiantong.qdata.quality.dal.dataobject.qa.DppQualityTaskObjDO;
import tech.qiantong.qdata.quality.dal.dataobject.qa.DppQualityTaskEvaluateDO;
import tech.qiantong.qdata.quality.service.qa.IDppQualityTaskService;
import tech.qiantong.qdata.quality.service.qa.IDppQualityTaskObjService;
import tech.qiantong.qdata.quality.service.qa.IDppQualityTaskEvaluateService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Freeze all rules and objects before a worker is queued; never persist connection credentials. */
@Service
@RequiredArgsConstructor
public class QualityExecutionSnapshots {
    private final IDppQualityTaskService tasks;
    private final IDppQualityTaskObjService objects;
    private final IDppQualityTaskEvaluateService rules;
    private final PlatformTransactionManager manager;
    private final JdbcTemplate jdbc;
    private final QualityExecutionProjectGuard projectGuard;
    public record Plan(long taskId,String taskName,String json,String sha256) {
        public List<DppQualityTaskObjDO> objects() { return JSON.parseObject(json).getJSONArray("objects").toJavaList(DppQualityTaskObjDO.class); }
        public List<DppQualityTaskEvaluateDO> rules() { return JSON.parseObject(json).getJSONArray("rules").toJavaList(DppQualityTaskEvaluateDO.class); }
        public DppQualityTaskRespVO task() { var task=new DppQualityTaskRespVO();task.setId(taskId);task.setTaskName(taskName);return task; }
    }
    public Plan capture(long taskId) {
        var transaction=new TransactionTemplate(manager);transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction.execute(status->{
            var bound=jdbc.queryForList("SELECT t.planned_json,t.log_id,a.status FROM dp_warehouse_quality_assessment_task t JOIN dp_warehouse_quality_assessment a ON a.id=t.assessment_id WHERE t.task_id=?",taskId);
            if(!bound.isEmpty()) {
                var row=bound.get(0);
                if(!"RUNNING".equals(row.get("status")) || row.get("log_id")!=null)
                    throw new IllegalArgumentException("固定质量评估已执行或尚未提交，请从批次创建新的评估尝试");
                var document=JSON.parseObject(String.valueOf(row.get("planned_json")));
                if(document.getLongValue("taskId")!=taskId)throw new IllegalArgumentException("固定质量任务身份不一致");
                var plan=freeze(taskId,document.getString("taskName"),document.getJSONArray("objects").toJavaList(DppQualityTaskObjDO.class),document.getJSONArray("rules").toJavaList(DppQualityTaskEvaluateDO.class));
                Long project=projectGuard.validate(plan);
                if(document.containsKey("projectId")){if(!Objects.equals(project,document.getLong("projectId")))throw new IllegalArgumentException("固定质量计划项目不一致");return bindProject(plan);}
                return plan; // Legacy immutable plans retain their exact content and hash.
            }
            var task=tasks.getDppQualityTaskById(taskId);
            if(task==null)throw new IllegalArgumentException("质量任务不存在");
            var selected=objects.getDppQualityTaskObjList(String.valueOf(taskId));
            if(selected==null || selected.isEmpty())throw new IllegalArgumentException("质量任务没有检查对象");
            var definitions=rules.getDppQualityTaskEvaluateList(selected.stream().map(DppQualityTaskObjDO::getId).toList());
            return bindProject(freeze(taskId,task.getTaskName(),selected,definitions));
        });
    }
    private Plan bindProject(Plan plan){
        Long project=projectGuard.validate(plan);var document=JSON.parseObject(plan.json());document.put("projectId",project);String json=JSON.toJSONString(document);
        try{return new Plan(plan.taskId(),plan.taskName(),json,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public void validateProject(Plan plan){projectGuard.validate(plan);}
    public void persist(long logId,Plan plan) {
        new TransactionTemplate(manager).executeWithoutResult(status->{
            var bindings=jdbc.queryForList("SELECT assessment_id FROM dp_warehouse_quality_assessment_task WHERE task_id=?",plan.taskId());
            if(!bindings.isEmpty()) {
                int claimed=jdbc.update("UPDATE dp_warehouse_quality_assessment_task t JOIN dp_warehouse_quality_assessment a ON a.id=t.assessment_id SET t.log_id=?,t.status='RUNNING' WHERE t.task_id=? AND t.log_id IS NULL AND t.status='READY' AND a.status='RUNNING'",logId,plan.taskId());
                if(claimed!=1)throw new IllegalArgumentException("质量评估日志已认领，不能覆盖已有执行证据");
            }
            jdbc.update("INSERT INTO dpp_quality_execution_snapshot(log_id,task_id,definition_json,definition_sha256) VALUES(?,?,?,?)",logId,plan.taskId(),plan.json(),plan.sha256());
        });
    }
    public void finished(long logId,boolean failed) {
        jdbc.update("UPDATE dp_warehouse_quality_assessment_task SET status=? WHERE log_id=? AND status='RUNNING'",failed?"FAILED":"EXECUTED",logId);
    }
    public static Plan freeze(long taskId,String name,List<DppQualityTaskObjDO> objects,List<DppQualityTaskEvaluateDO> rules) {
        Map<Long,DppQualityTaskObjDO> objectIds=new LinkedHashMap<>();
        for(var object:objects) {
            if(object.getId()==null || object.getDatasourceId()==null || !Objects.equals(object.getTaskId(),taskId)
                    || object.getTableName()==null || object.getTableName().isBlank() || objectIds.put(object.getId(),object)!=null)
                throw new IllegalArgumentException("质量检查对象身份缺失或重复");
        }
        if(objectIds.isEmpty())throw new IllegalArgumentException("质量任务没有检查对象");
        if(rules==null)throw new IllegalArgumentException("质量任务没有检查规则");
        var enabled=rules.stream().filter(rule->"1".equals(rule.getStatus())).toList();
        if(enabled.isEmpty())throw new IllegalArgumentException("质量任务没有启用的检查规则");
        Set<Long> ruleIds=new HashSet<>();
        for(var rule:enabled) {
            var object=objectIds.get(rule.getObjId());
            if(rule.getId()==null || !ruleIds.add(rule.getId()) || !Objects.equals(rule.getTaskId(),taskId)
                    || object==null || !Objects.equals(rule.getTableName(),object.getTableName()))
                throw new IllegalArgumentException("质量规则与检查对象不一致");
        }
        enabled=enabled.stream().sorted(Comparator.comparing(DppQualityTaskEvaluateDO::getId)).toList();
        Map<String,Object> content=new LinkedHashMap<>();content.put("formatVersion",1);content.put("taskId",taskId);content.put("taskName",name);
        content.put("objects",objects.stream().sorted(Comparator.comparing(DppQualityTaskObjDO::getId))
                .map(object->fields(object,List.of("id","taskId","name","datasourceId","tableName"))).toList());
        content.put("rules",enabled.stream().map(rule->fields(rule,List.of("id","taskId","objId","objName","name","ruleCode","ruleName","warningLevel","status","dimensionType","ruleType","ruleDescription","errDescription","suggestion","whereClause","tableName","evaColumn","rule"))).toList());
        String json=JSON.toJSONString(content);
        try { return new Plan(taskId,name,json,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Map<String,Object> fields(Object object,List<String> names) {
        var bean=new BeanWrapperImpl(object);Map<String,Object> selected=new LinkedHashMap<>();
        names.forEach(name->selected.put(name,bean.getPropertyValue(name)));return selected;
    }
}
