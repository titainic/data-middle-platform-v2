package tech.qiantong.qdata.module.dpp.service.qa;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.*;
import java.util.*;

/** Resolve reports and issues through their owning task before returning data or performing an action. */
@Service
@RequiredArgsConstructor
public class QualityResourceAccess {
    private final JdbcTemplate jdbc;
    private final DataResourceAccess resources;
    private final QualityProjectAccess projects;
    public List<Long> visibleTasks(){return visibleTasks("info",null);}
    public List<Long> visibleTasks(String action,Long project){
        var permitted=new HashSet<>(projects.tasks(action,project,false));
        List<Long> visible=new ArrayList<>();
        for(Long id:permitted)try{definitionReferences(id);visible.add(id);}catch(AccessDeniedException|IllegalArgumentException denied){/* Unavailable references do not expand task visibility. */}
        return visible;
    }
    private void definitionReferences(Long id){
        var task=projects.row(id,false);Long project=QualityProjectAccess.number(task.get("project_id")),asset=QualityProjectAccess.number(task.get("asset_id"));
        if(project!=null)projects.asset(project,asset);else if(asset!=null && asset>0)resources.requireAsset(asset);
        for(var row:jdbc.queryForList("SELECT datasource_id,table_name FROM dpp_quality_task_obj WHERE task_id=? AND del_flag='0'",id)){
            Long source=nullable(row.get("datasource_id"));String table=String.valueOf(row.get("table_name"));
            if(project!=null)projects.target(project,source,table,false);else resources.requireMetadataTable(source,table);
        }
    }
    public void task(Long id){task(id,"info");}
    public void task(Long id,String action){projects.task(id,action,false);if(!Set.of("info","index").contains(action))nativeDefinition(id);if("info".equals(action) || "index".equals(action))definitionReferences(id);}
    public void execute(Long id,String action){projects.task(id,action,false);nativeDefinition(id);projects.execute(id,action);}
    private void nativeDefinition(Long id){if(id==null || jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_quality_assessment_task WHERE task_id=?",Long.class,id)>0)throw new AccessDeniedException("固定数仓评估任务请通过评估入口管理");}
    public List<Map<String,Object>> projectOptions(){return projects.projects();}
    public void present(tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.DppQualityTaskRespVO value){if(value==null)return;Long project=QualityProjectAccess.number(projects.row(value.getId(),false).get("project_id"));value.setProjectId(project);value.setProjectName(project==null?"未确认归属":jdbc.queryForObject("SELECT name FROM att_project WHERE id=?",String.class,project));value.setCapabilities(projects.capabilities(project));}
    public Long object(Long id){return object(id,"info");}
    public Long object(Long id,String action){return childTask("dpp_quality_task_obj",id,action);}
    public Long rule(Long id){return rule(id,"info");}
    public Long rule(Long id,String action){return childTask("dpp_quality_task_evaluate",id,action);}
    private Long childTask(String table,Long id,String action){var rows=jdbc.queryForList("SELECT task_id FROM "+table+" WHERE id=? AND del_flag='0'",Long.class,id);if(rows.size()!=1)throw new AccessDeniedException("质量对象不存在或无权访问");task(rows.get(0),action);return rows.get(0);}
    public void log(Long id){log(id,"log");}
    public void log(Long id,String action){var rows=jdbc.queryForList("SELECT quality_id FROM dpp_quality_log WHERE id=? AND del_flag='0'",Long.class,id);if(rows.size()!=1)throw new AccessDeniedException("质量日志不存在或无权访问");projects.task(rows.get(0),action,true);}
    public void report(String id){long identity=parse(id);var rows=jdbc.queryForList("SELECT task_log_id FROM dpp_evaluate_log WHERE id=? AND del_flag='0'",String.class,identity);if(rows.size()!=1)throw new AccessDeniedException("质量报告不存在或无权访问");log(parse(rows.get(0)));}
    public List<Long> visibleLogTasks(){return projects.tasks("log",null,true);}
    public List<Long> visibleLogs(){var tasks=projects.tasks("log",null,true);if(tasks.isEmpty())return List.of();return jdbc.queryForList("SELECT id FROM dpp_quality_log WHERE del_flag='0' AND quality_id IN ("+String.join(",",Collections.nCopies(tasks.size(),"?"))+")",Long.class,tasks.toArray());}
    public Map<String,Boolean> problemCapabilities(String reportId){report(reportId);Long task=jdbc.queryForObject("SELECT q.quality_id FROM dpp_evaluate_log e JOIN dpp_quality_log q ON q.id=e.task_log_id WHERE e.id=?",Long.class,parse(reportId));return projects.capabilities(QualityProjectAccess.number(projects.row(task,true).get("project_id")));}
    public void errorData(CheckErrorDataReqDTO request) {errorData(request,false);}
    public void errorData(CheckErrorDataReqDTO request,boolean write) {
        report(request.getReportId());
        if(write){Long task=jdbc.queryForObject("SELECT q.quality_id FROM dpp_evaluate_log e JOIN dpp_quality_log q ON q.id=e.task_log_id WHERE e.id=?",Long.class,parse(request.getReportId()));projects.task(task,"edit",true);}
        Set<String> ids=new HashSet<>();if(request.getId()!=null && !request.getId().isBlank())ids.add(request.getId());if(request.getErrorDataId()!=null)ids.addAll(request.getErrorDataId());
        for(String id:ids) {
            var reports=jdbc.queryForList("SELECT report_id FROM quality_error_data WHERE id=?",String.class,id);
            if(reports.size()!=1 || !request.getReportId().equals(reports.get(0)))throw new AccessDeniedException("问题记录不属于所选质量报告");
        }
        if("1".equals(request.getUpdateType())) {
            var targets=jdbc.queryForList("SELECT o.datasource_id,l.table_name,o.table_name object_table,d.datasource_type FROM dpp_evaluate_log l JOIN dpp_quality_task_evaluate r ON r.id=l.evaluate_id JOIN dpp_quality_task_obj o ON o.id=r.obj_id JOIN da_datasource d ON d.id=o.datasource_id WHERE l.id=?",parse(request.getReportId()));
            if(targets.size()!=1)throw new AccessDeniedException("原报告的来源对象无法确认，不能执行原地修复");
            var target=targets.get(0);
            if("Hive".equalsIgnoreCase(String.valueOf(target.get("datasource_type"))))throw new IllegalArgumentException("Hive问题请通过新批次修复并复评，原版本不允许原地改写");
            if(!Objects.equals(target.get("table_name"),target.get("object_table")))throw new AccessDeniedException("来源对象已变化，请重新评估后处理");
            request.setDatasourceId(number(target.get("datasource_id")));request.setTableName(String.valueOf(target.get("table_name")));
            Long task=jdbc.queryForObject("SELECT q.quality_id FROM dpp_evaluate_log e JOIN dpp_quality_log q ON q.id=e.task_log_id WHERE e.id=?",Long.class,parse(request.getReportId()));Long project=projects.task(task,write?"edit":"log",true);projects.target(project,request.getDatasourceId(),request.getTableName(),true);
        }
    }
    public void validateTask(DppQualityTaskSaveReqVO request){validateTask(request,request.getId()==null?"add":"edit");}
    public void validateTask(DppQualityTaskSaveReqVO request,String action) {
        if(request.getId()!=null){Long owner=projects.task(request.getId(),action,false);nativeDefinition(request.getId());if(request.getProjectId()!=null && !request.getProjectId().equals(owner))throw new AccessDeniedException("质量任务不能改归属项目");request.setProjectId(owner);request.setStatus(String.valueOf(projects.row(request.getId(),false).get("status")));request.setCreatorId(null);request.setCreateBy(null);request.setCreateTime(null);}
        else {request.setProjectId(projects.chooseProject(request.getProjectId(),request.getAssetId(),action));request.setStatus("1");}
        if(request.getId()!=null){var stored=projects.row(request.getId(),false);request.setCycle(Objects.toString(stored.get("cycle"),null));request.setTaskId(nullable(stored.get("task_id")));request.setTaskCode(Objects.toString(stored.get("task_code"),null));request.setNodeId(nullable(stored.get("node_id")));request.setNodeCode(Objects.toString(stored.get("node_code"),null));request.setSystemJobId(nullable(stored.get("system_job_id")));}
        else {request.setTaskId(null);request.setTaskCode(null);request.setNodeId(null);request.setNodeCode(null);request.setSystemJobId(null);}
        projects.asset(request.getProjectId(),request.getAssetId());
        if(request.getDppQualityTaskObjSaveReqVO()==null)request.setDppQualityTaskObjSaveReqVO(List.of());
        Long owner=request.getId();
        if(owner==null && "1".equals(request.getAssetFlag()) && request.getAssetId()!=null){var existing=jdbc.queryForList("SELECT id FROM dpp_quality_task WHERE asset_id=? AND asset_flag='1' AND del_flag='0' AND project_id=?",Long.class,request.getAssetId(),request.getProjectId());if(existing.size()==1){owner=existing.get(0);task(owner,"info");}}
        if(request.getDppQualityTaskObjSaveReqVO()!=null)for(var object:request.getDppQualityTaskObjSaveReqVO()) {
            projects.target(request.getProjectId(),object.getDatasourceId(),object.getTableName(),false);
            if(object.getId()!=null)requireChildOwner("dpp_quality_task_obj",object.getId(),owner);
        }
        if(request.getDppQualityTaskEvaluateSaveReqVO()!=null)for(var rule:request.getDppQualityTaskEvaluateSaveReqVO()){
            if(rule==null)throw new IllegalArgumentException("质量规则不能为空");
            if(rule.getId()!=null)requireChildOwner("dpp_quality_task_evaluate",rule.getId(),owner);
            if(rule.getObjId()!=null)requireChildOwner("dpp_quality_task_obj",rule.getObjId(),owner);
            if(request.getDppQualityTaskObjSaveReqVO().stream().noneMatch(object->Objects.equals(object.getDatasourceId(),rule.getDatasourceId()) && Objects.equals(object.getTableName(),rule.getTableName())))throw new IllegalArgumentException("质量规则没有对应的检查对象");

        }
    }
    private void requireChildOwner(String table,Long id,Long taskId){var owners=jdbc.queryForList("SELECT task_id FROM "+table+" WHERE id=?",Long.class,id);if(owners.size()!=1 || !Objects.equals(owners.get(0),taskId))throw new AccessDeniedException("不能修改或引用其他质量任务的对象或规则");}
    public void validateObject(DppQualityTaskObjSaveReqVO request){
        if(request.getId()!=null){Long parent=object(request.getId(),"edit");if(request.getTaskId()!=null && !parent.equals(request.getTaskId()))throw new AccessDeniedException("不能移动检查对象");request.setTaskId(parent);var old=jdbc.queryForMap("SELECT datasource_id,table_name FROM dpp_quality_task_obj WHERE id=?",request.getId());if(request.getDatasourceId()==null)request.setDatasourceId(number(old.get("datasource_id")));if(request.getTableName()==null)request.setTableName(String.valueOf(old.get("table_name")));}
        Long project=projects.task(request.getTaskId(),"edit",false);projects.target(project,request.getDatasourceId(),request.getTableName(),false);
    }
    public void validateRule(DppQualityTaskEvaluateSaveReqVO request){
        if(request.getId()!=null){Long parent=rule(request.getId(),"edit");if(request.getTaskId()!=null && !parent.equals(request.getTaskId()))throw new AccessDeniedException("不能移动质量规则");request.setTaskId(parent);if(request.getObjId()==null)request.setObjId(jdbc.queryForObject("SELECT obj_id FROM dpp_quality_task_evaluate WHERE id=?",Long.class,request.getId()));}
        Long project=projects.task(request.getTaskId(),"edit",false);
        if(request.getObjId()==null || !Objects.equals(object(request.getObjId(),"edit"),request.getTaskId()))throw new AccessDeniedException("规则必须引用本任务的检查对象");
        var target=jdbc.queryForMap("SELECT datasource_id,table_name FROM dpp_quality_task_obj WHERE id=?",request.getObjId());Long source=number(target.get("datasource_id"));String table=String.valueOf(target.get("table_name"));
        if(request.getDatasourceId()!=null && !source.equals(request.getDatasourceId()) || request.getTableName()!=null && !table.equals(request.getTableName()))throw new AccessDeniedException("规则来源与检查对象不一致");
        request.setDatasourceId(source);request.setTableName(table);projects.target(project,source,table,false);
    }
    public void previewRule(DppQualityTaskEvaluateSaveReqVO request){
        Set<Long> parents=new HashSet<>();if(request.getId()!=null)parents.add(rule(request.getId()));if(request.getTaskId()!=null){task(request.getTaskId());parents.add(request.getTaskId());}if(request.getObjId()!=null)parents.add(object(request.getObjId()));
        if(parents.size()>1)throw new AccessDeniedException("预览对象和规则不属于同一任务");
        Long project=parents.isEmpty()?projects.chooseProject(request.getProjectId(),null,"info"):projects.task(parents.iterator().next(),"info",false);
        if(request.getProjectId()!=null && !Objects.equals(project,request.getProjectId()))throw new AccessDeniedException("预览项目与实际任务不一致");
        projects.target(project,request.getDatasourceId(),request.getTableName(),true);
    }
    public Long resolveAssetTask(Long asset,Long requestedProject){
        resources.requireAsset(asset);var ids=jdbc.queryForList("SELECT id FROM dpp_quality_task WHERE asset_id=? AND asset_flag='1' AND del_flag='0'"+(requestedProject==null?"":" AND project_id=?"),Long.class,requestedProject==null?new Object[]{asset}:new Object[]{asset,requestedProject});
        List<Long> allowed=new ArrayList<>();for(Long id:ids)try{task(id);allowed.add(id);}catch(AccessDeniedException denied){}
        if(allowed.size()>1)throw new AccessDeniedException("此资产有多个项目的质量任务，请明确项目");return allowed.isEmpty()?null:allowed.get(0);
    }
    public void asset(Long id){resources.requireAsset(id);}
    public void logPath(String path){var rows=jdbc.queryForList("SELECT id FROM dpp_quality_log WHERE path=? AND del_flag='0'",Long.class,path);if(rows.isEmpty())throw new AccessDeniedException("质量日志不存在或无权访问");for(Long id:rows)log(id);}
    public static long parse(String value){if(value==null || !value.matches("[0-9]+"))throw new AccessDeniedException("质量记录标识无效");try{return Long.parseLong(value);}catch(NumberFormatException invalid){throw new AccessDeniedException("质量记录标识无效");}}
    private static Long nullable(Object value){return value==null?null:number(value);}
    private static long number(Object value){return value instanceof Number?((Number)value).longValue():parse(String.valueOf(value));}
}
