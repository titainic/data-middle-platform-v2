package tech.qiantong.qdata.module.mc.service.task;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;

/** Task ownership is explicit; functional grants must belong to that same project. */
@Service
@RequiredArgsConstructor
public class CollectionProjectAccess {
    private final JdbcTemplate jdbc;
    private final DataResourceAccess data;
    private final ProjectPermissionService permissions;

    private boolean granted(Long project,String action){
        if("read".equals(action))return permissions.any(project,"mc:task:list","mc:task:query","mc:metadata:table:list","mc:metadata:table:query","mc:metadata:table:detail");
        String alias=Set.of("add","remove").contains(action)?action:Set.of("export","import").contains(action)?("export".equals(action)?"list":"add"):"edit";
        return permissions.any(project,"mc:task:"+action,"mc:metadata:table:"+alias);
    }
    public void project(Long id,String action){if(id==null || !data.visibleProjectIds().contains(id) || !granted(id,action))throw denied();}
    public boolean allowed(Long id,String action){return id!=null && data.visibleProjectIds().contains(id) && granted(id,action);}
    public Map<String,Boolean> capabilities(Long id){
        Map<String,Boolean> result=new LinkedHashMap<>();
        for(String action:List.of("read","add","edit","remove","release","schedule","execute","export","import"))result.put(action,allowed(id,action));
        return result;
    }
    public List<Map<String,Object>> projects(){
        List<Map<String,Object>> result=new ArrayList<>();
        for(Long id:data.visibleProjectIds())if(granted(id,"read") || granted(id,"add")){
            var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT id,name FROM att_project WHERE id=?",id));row.put("capabilities",capabilities(id));result.add(row);
        }
        return result;
    }
    public List<Map<String,Object>> sources(Long project){
        if(!allowed(project,"read") && !allowed(project,"add"))throw denied();
        var rows=jdbc.queryForList("SELECT d.id,d.datasource_name datasourceName,d.datasource_type datasourceType,d.ip,d.port,d.valid_flag validFlag,d.datasource_config datasourceConfig FROM da_datasource d WHERE d.del_flag='0' AND EXISTS(SELECT 1 FROM da_datasource_project_rel r WHERE r.datasource_id=d.id AND r.project_id=? AND r.valid_flag='1') ORDER BY d.id",project);
        for(var row:rows)row.put("datasourceConfig",tech.qiantong.qdata.common.database.core.ConnectionDisplayConfig.sanitize((String)row.get("datasourceConfig")));return rows;
    }
    private void lookupProject(Long project){if(!allowed(project,"read") && !allowed(project,"add"))throw denied();}
    public List<Map<String,Object>> sourceSystems(Long project){lookupProject(project);return jdbc.queryForList("SELECT id,name FROM att_source_system WHERE del_flag='0' AND valid_flag='1' ORDER BY sort_order,id");}
    public List<Map<String,Object>> leaders(Long project){
        lookupProject(project);
        return jdbc.queryForList("SELECT u.user_id userId,u.nick_name nickName,u.phonenumber FROM system_user u WHERE u.del_flag='0' AND u.status='0' AND (EXISTS(SELECT 1 FROM att_project p WHERE p.id=? AND p.manager_id=u.user_id) OR EXISTS(SELECT 1 FROM att_project_user_rel r WHERE r.project_id=? AND r.user_id=u.user_id AND r.del_flag='0' AND r.valid_flag='1')) ORDER BY u.user_id",project,project);
    }
    public void references(Long project,tech.qiantong.qdata.module.mc.controller.admin.task.vo.McTaskSaveReqVO request){
        if(request.getSourceSystemId()!=null){var names=jdbc.queryForList("SELECT name FROM att_source_system WHERE id=? AND valid_flag='1' AND del_flag='0'",String.class,request.getSourceSystemId());if(names.size()!=1)throw new IllegalArgumentException("来源系统不存在或已停用");request.setSourceSystemName(names.get(0));}
        if(request.getLeader()!=null){var member=leaders(project).stream().filter(row->Objects.equals(number(row.get("userId")),request.getLeader())).findFirst().orElseThrow(()->new AccessDeniedException("责任人必须是当前项目的有效成员"));request.setLeaderPhone(Objects.toString(member.get("phonenumber"),""));}
    }
    public Set<Long> sourceProjects(Long source){return new HashSet<>(jdbc.queryForList("SELECT DISTINCT project_id FROM da_datasource_project_rel WHERE datasource_id=? AND valid_flag='1'",Long.class,source));}
    public Long choose(Long requested,Long source){
        if(requested!=null){project(requested,"add");return requested;}
        var candidates=sourceProjects(source).stream().filter(p->allowed(p,"add")).toList();
        if(candidates.size()!=1)throw new AccessDeniedException("请选择有权限的采集任务所属项目");return candidates.get(0);
    }
    public void task(Map<String,Object> row,String action){
        Long owner=number(row.get("project_id"));
        if(owner!=null){project(owner,action);return;}
        if(!"read".equals(action) || !permissions.isAdministrator() && (!Objects.equals(number(row.get("creator_id")),SecurityUtils.getUserId()) || !SecurityUtils.hasPermi("mc:metadata:table:list")))throw denied();
    }
    public void source(Long project,Long source,boolean execute){
        if(project==null || !sourceProjects(source).contains(project))throw denied();
        if(execute)data.requireDatasource(source);else data.requireDatasourceConfiguration(source);
    }
    public void namespace(Long project,Long source,String database){
        if(database==null || database.isBlank())throw denied();data.requireMetadataTable(source,database+".__metadata_scope");
        var owners=data.owningHiveProjects(source,database+".__metadata_scope");
        if(!owners.isEmpty() && (owners.size()!=1 || !owners.contains(project)))throw denied();
    }
    public boolean history(Long source,String database){
        var owners=data.owningHiveProjects(source,database+".__metadata_scope");if(owners.isEmpty())owners=sourceProjects(source);
        return owners.stream().anyMatch(p->allowed(p,"read"));
    }
    public static Long number(Object value){return value instanceof Number n?n.longValue():null;}
    private static AccessDeniedException denied(){return new AccessDeniedException("采集任务或来源不属于当前项目的操作权限范围");}
}
