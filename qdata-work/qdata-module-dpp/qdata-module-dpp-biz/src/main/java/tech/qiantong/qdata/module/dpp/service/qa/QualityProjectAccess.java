package tech.qiantong.qdata.module.dpp.service.qa;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;

/** Quality capabilities always belong to the same project as the saved task. */
@Service
@RequiredArgsConstructor
public class QualityProjectAccess {
    private final JdbcTemplate jdbc;
    private final DataResourceAccess data;
    private final ProjectPermissionService permissions;
    private String permission(String action){return "log".equals(action)?"da:qualityLog:index":"da:qualityTask:"+action;}
    public void project(Long id,String action){if(id==null || !data.visibleProjectIds().contains(id) || !permissions.has(id,permission(action)))throw denied();}
    public List<Map<String,Object>> projects(){
        List<Map<String,Object>> result=new ArrayList<>();
        for(Long id:data.visibleProjectIds())if(permissions.any(id,"da:qualityTask:index","da:qualityTask:info","da:qualityTask:add","da:qualityLog:index")){
            var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT id,name FROM att_project WHERE id=?",id));row.put("capabilities",capabilities(id));result.add(row);
        }
        return result;
    }
    public Map<String,Boolean> capabilities(Long project){
        Map<String,Boolean> result=new LinkedHashMap<>();
        for(String action:List.of("index","info","add","edit","remove","once","schedule","log"))result.put(action,project!=null && data.visibleProjectIds().contains(project) && permissions.has(project,permission(action)));
        return result;
    }
    public Map<String,Object> row(Long id,boolean history){
        if(id==null)throw denied();var rows=jdbc.queryForList("SELECT id,project_id,creator_id,asset_id,asset_flag,status,cycle,task_id,task_code,node_id,node_code,system_job_id FROM dpp_quality_task WHERE id=?"+(history?"":" AND del_flag='0'"),id);
        if(rows.size()!=1)throw denied();return rows.get(0);
    }
    public Long task(Long id,String action,boolean history){
        var row=row(id,history);Long project=number(row.get("project_id"));
        if(project!=null){project(project,action);return project;}
        if(!Set.of("index","info","log").contains(action) || !permissions.isAdministrator() && (!Objects.equals(number(row.get("creator_id")),SecurityUtils.getUserId()) || !SecurityUtils.hasPermi(permission(action))))throw denied();
        return null;
    }
    public List<Long> tasks(String action,Long project,boolean history){
        if(project!=null)project(project,action);Set<Long> visible=data.visibleProjectIds();boolean admin=permissions.isAdministrator();Map<Long,Boolean> grants=new HashMap<>();List<Long> ids=new ArrayList<>();
        for(var row:jdbc.queryForList("SELECT id,project_id,creator_id FROM dpp_quality_task"+(history?"":" WHERE del_flag='0'"))){
            Long owner=number(row.get("project_id"));if(project!=null && !project.equals(owner))continue;
            if(owner!=null && visible.contains(owner) && grants.computeIfAbsent(owner,p->permissions.has(p,permission(action))) || owner==null && Set.of("index","info","log").contains(action) && (admin || Objects.equals(number(row.get("creator_id")),SecurityUtils.getUserId()) && SecurityUtils.hasPermi(permission(action))))ids.add(number(row.get("id")));
        }
        return ids;
    }
    public Set<Long> assetProjects(Long id){
        if(id==null || id<=0)return Set.of();
        var spaces=jdbc.queryForList("SELECT s.project_id FROM da_asset a JOIN dp_warehouse_dataset d ON d.id=a.dataset_id JOIN dp_warehouse_space s ON s.id=d.space_id WHERE a.id=?",Long.class,id);
        if(!spaces.isEmpty())return new HashSet<>(spaces);
        return new HashSet<>(jdbc.queryForList("SELECT project_id FROM da_asset_project_rel WHERE asset_id=? AND del_flag='0' AND valid_flag='1'",Long.class,id));
    }
    public Long chooseProject(Long requested,Long asset,String action){
        if(requested!=null){project(requested,action);return requested;}
        Set<Long> owners=assetProjects(asset);var eligible=data.visibleProjectIds().stream().filter(p->(asset==null || asset<=0 || owners.contains(p)) && permissions.has(p,permission(action))).toList();
        if(eligible.size()!=1)throw new AccessDeniedException("请选择有权限的质量任务所属项目");return eligible.get(0);
    }
    public void asset(Long project,Long id){if(id==null || id<=0)return;data.requireAsset(id);if(!assetProjects(id).contains(project))throw denied();}
    public void target(Long project,Long source,String table,boolean execute){
        if(project==null)throw denied();if(execute)data.requireTable(source,table);else data.requireMetadataTable(source,table);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM da_datasource_project_rel WHERE datasource_id=? AND project_id=? AND valid_flag='1'",Long.class,source,project)<1)throw denied();
        var owners=data.owningHiveProjects(source,table);if(!owners.isEmpty() && (owners.size()!=1 || !owners.contains(project)))throw new AccessDeniedException("质量任务不能引用其他项目的受管Hive数据库");
    }
    public void execute(Long id,String action){
        Long project=task(id,action,false);var targets=jdbc.queryForList("SELECT datasource_id,table_name FROM dpp_quality_task_obj WHERE task_id=? AND del_flag='0'",id);
        if(targets.isEmpty())throw new IllegalArgumentException("质量任务尚未配置检查对象");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM dpp_quality_task_evaluate WHERE task_id=? AND del_flag='0' AND status='1'",Long.class,id)==0)throw new IllegalArgumentException("质量任务尚未启用检查规则");
        for(var target:targets)target(project,number(target.get("datasource_id")),String.valueOf(target.get("table_name")),true);
    }
    public void sourceMetadata(Long id){Long project=task(id,"info",false);for(var row:jdbc.queryForList("SELECT datasource_id,table_name FROM dpp_quality_task_obj WHERE task_id=? AND del_flag='0'",id))target(project,number(row.get("datasource_id")),String.valueOf(row.get("table_name")),false);}
    public static Long number(Object value){return value instanceof Number n?n.longValue():null;}
    private static AccessDeniedException denied(){return new AccessDeniedException("质量任务或检查对象不属于当前项目的操作权限范围");}
}
