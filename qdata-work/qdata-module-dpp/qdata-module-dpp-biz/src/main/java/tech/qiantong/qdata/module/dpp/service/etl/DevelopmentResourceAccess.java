package tech.qiantong.qdata.module.dpp.service.etl;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNewNodeSaveReqVO;
import java.util.*;

/** Resolve the owning project before reading or changing a development resource. */
@Service
@RequiredArgsConstructor
public class DevelopmentResourceAccess implements tech.qiantong.qdata.module.dpp.api.service.DevelopmentManagementAccess {
    @Override public List<Long> readableTaskIds(){return taskIds();}
    @Override public Long requireReadableTaskProject(Long id){return number(task(id).get("project_id"));}
    private final JdbcTemplate jdbc;
    private final DataResourceAccess resources;
    private final tech.qiantong.qdata.security.web.service.ProjectPermissionService permissions;
    @org.springframework.beans.factory.annotation.Autowired private DevelopmentFileAccessService developmentFiles;
    public List<Long> taskIds(){
        var projects=resources.visibleProjectIds();
        if(!resources.isAdministrator() && projects.isEmpty())return List.of();
        String filter=resources.isAdministrator()?"":" AND project_id IN ("+marks(projects.size())+")";
        var rows=jdbc.queryForList("SELECT id,project_id,type FROM dpp_etl_task WHERE del_flag='0'"+filter,resources.isAdministrator()?new Object[0]:projects.toArray());
        Map<String,Boolean> grants=new HashMap<>();
        return rows.stream().filter(row->grants.computeIfAbsent(row.get("project_id")+":"+row.get("type"),key->allowed(number(row.get("project_id")),String.valueOf(row.get("type")),"read"))).map(row->((Number)row.get("id")).longValue()).toList();
    }
    public void readType(String type){if(!resources.isAdministrator() && resources.visibleProjectIds().stream().noneMatch(project->allowed(project,type==null?"1":type,"read")))throw denied();}
    public void readType(String type,Long project){operation(project,type==null?"1":type,"read");}
    public void project(Long id,String code){
        if(id==null || !resources.visibleProjectIds().contains(id))throw denied();
        var rows=jdbc.queryForList("SELECT code FROM att_project WHERE id=? AND del_flag='0' AND valid_flag='1'",String.class,id);
        if(rows.size()!=1 || code!=null && !code.isBlank() && !rows.get(0).equals(code))throw denied();
    }
    public Map<String,Object> task(Long id){return task(id,"read");}
    public Map<String,Object> task(Long id,String action){
        if(id==null)throw denied();
        var rows=jdbc.queryForList("SELECT id,project_id,project_code,code,type FROM dpp_etl_task WHERE id=? AND del_flag='0'",id);
        if(rows.size()!=1)throw denied();var row=rows.get(0);
        project(number(row.get("project_id")),Objects.toString(row.get("project_code"),null));operation(number(row.get("project_id")),String.valueOf(row.get("type")),action);return row;
    }
    public Map<String,Object> taskCode(String code,String projectCode){
        if(code==null || code.isBlank())throw denied();
        var ids=projectCode==null || projectCode.isBlank()?jdbc.queryForList("SELECT id FROM dpp_etl_task WHERE code=? AND del_flag='0'",Long.class,code):jdbc.queryForList("SELECT id FROM dpp_etl_task WHERE code=? AND project_code=? AND del_flag='0'",Long.class,code,projectCode);
        if(ids.size()!=1)throw denied();return task(ids.get(0));
    }
    public void request(DppEtlNewNodeSaveReqVO request,String action){
        if(request.getId()!=null && !request.getId().isBlank()) {
            var existing=task(parse(request.getId()),action.equals("add")?"read":action);
            if(request.getProjectId()!=null && !request.getProjectId().equals(number(existing.get("project_id"))))throw denied();
            if(request.getProjectCode()!=null && !request.getProjectCode().toString().equals(String.valueOf(existing.get("project_code"))))throw denied();
            if(request.getCode()!=null && !request.getCode().isBlank() && !request.getCode().equals(existing.get("code")))throw denied();
            if(request.getType()!=null && !request.getType().isBlank() && !request.getType().equals(String.valueOf(existing.get("type"))))throw denied();
            request.setProjectId(number(existing.get("project_id")));request.setProjectCode(Long.valueOf(String.valueOf(existing.get("project_code"))));request.setType(String.valueOf(existing.get("type")));
        } else if(!action.equals("add"))throw denied();
        project(request.getProjectId(),request.getProjectCode()==null?null:request.getProjectCode().toString());operation(request.getProjectId(),request.getType()==null?"1":request.getType(),action);
        if(request.getCatCode()!=null && !request.getCatCode().isBlank()){
            String table=("3".equals(request.getType())||"4".equals(request.getType()))?"att_data_dev_cat":"att_task_cat";
            var owners=jdbc.queryForList("SELECT project_id FROM "+table+" WHERE code=? AND del_flag='0'",Long.class,request.getCatCode());
            if(owners.size()!=1 || !Objects.equals(owners.get(0),request.getProjectId()))throw new AccessDeniedException("任务类目不属于所选项目");
        }
        if("DOLPHINSCHEDULER".equalsIgnoreCase(request.getScheduler()) && "QUARTZ".equals(jdbc.queryForObject("SELECT scheduler_type FROM att_project WHERE id=?",String.class,request.getProjectId())))throw new IllegalArgumentException("当前项目使用本地调度，不能向外部调度提交未登记的项目编码");
        definitions(request);
        if(developmentFiles!=null && request.getId()!=null && ("1".equals(request.getReleaseState()) || "1".equals(request.getSchedulerState())))developmentFiles.task(parse(request.getId()));
    }
    private void definitions(DppEtlNewNodeSaveReqVO request){
        if(request.getTaskDefinitionList()==null || request.getTaskDefinitionList().isBlank())return;
        var nodes=com.alibaba.fastjson2.JSON.parseArray(request.getTaskDefinitionList(),com.alibaba.fastjson2.JSONObject.class);
        if(nodes==null)throw new IllegalArgumentException("节点定义必须是JSON数组");
        for(var node:nodes){
            String code=node.getString("code");if(code==null || code.isBlank())throw new IllegalArgumentException("节点缺少编码");
            var owners=jdbc.queryForList("SELECT project_id FROM dpp_etl_node WHERE code=? UNION SELECT project_id FROM dpp_etl_node_log WHERE code=?",Long.class,code,code);
            if(owners.stream().anyMatch(owner->!Objects.equals(owner,request.getProjectId())))throw new AccessDeniedException("节点编码已属于另一个项目，请为当前项目创建新节点");
            var params=node.getJSONObject("taskParams");String subtask=params==null?null:params.getString("processDefinitionCode");
            if(subtask!=null && !subtask.isBlank() && !"0".equals(subtask)){
                var child=taskCode(subtask,null);if(!Objects.equals(number(child.get("project_id")),request.getProjectId()))throw denied();
            }
        }
        if(developmentFiles!=null)developmentFiles.definitions(request.getProjectId(),request.getTaskDefinitionList());
    }
    public void instance(Long id){
        instance(id,"read");
    }
    public void instance(Long id,String action){
        var ids=jdbc.queryForList("SELECT task_id FROM dpp_etl_task_instance WHERE id=? AND del_flag='0'",Long.class,id);
        if(ids.size()!=1)throw denied();task(ids.get(0),action);
    }
    public Long instanceTask(Long id){instance(id);return jdbc.queryForObject("SELECT task_id FROM dpp_etl_task_instance WHERE id=?",Long.class,id);}
    public Long resolveProject(Long id,String code){
        if(id==null && code!=null && !code.isBlank()){var found=jdbc.queryForList("SELECT id FROM att_project WHERE code=? AND del_flag='0'",Long.class,code);if(found.size()!=1)throw denied();id=found.get(0);}
        if(id==null){if(resources.isAdministrator())return null;var projects=resources.visibleProjectIds();if(projects.size()!=1)throw denied();id=projects.iterator().next();}
        project(id,code);return id;
    }
    public void instanceRequest(tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlTaskInstanceSaveReqVO request,boolean existing){
        if(existing){Long taskId=instanceTask(request.getId());if(request.getTaskId()!=null && !taskId.equals(request.getTaskId()))throw denied();request.setTaskId(taskId);}
        var parent=task(request.getTaskId(),"edit");
        if(request.getProjectId()!=null && !request.getProjectId().equals(number(parent.get("project_id"))))throw denied();
        if(request.getProjectCode()!=null && !request.getProjectCode().equals(String.valueOf(parent.get("project_code"))))throw denied();
        if(request.getTaskCode()!=null && !request.getTaskCode().equals(String.valueOf(parent.get("code"))))throw denied();
        request.setProjectId(number(parent.get("project_id")));request.setProjectCode(String.valueOf(parent.get("project_code")));request.setTaskCode(String.valueOf(parent.get("code")));
    }
    public void nodeInInstance(Long instanceId,Long nodeId){instance(instanceId);nodeInstance(nodeId);if(jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_node_instance WHERE id=? AND task_instance_id=?",Long.class,nodeId,instanceId)!=1)throw denied();}
    public void nodeInstance(Long id){
        var ids=jdbc.queryForList("SELECT task_instance_id FROM dpp_etl_node_instance WHERE id=? AND del_flag='0'",Long.class,id);
        if(ids.size()!=1)throw denied();instance(ids.get(0));
    }
    public enum Child {
        NODE("dpp_etl_node","r.task_type"), NODE_LOG("dpp_etl_node_log","r.task_type"),
        TASK_LOG("dpp_etl_task_log","r.type"), RELATION("dpp_etl_task_node_rel","t.type"),
        RELATION_LOG("dpp_etl_task_node_rel_log","t.type"), NODE_INSTANCE("dpp_etl_node_instance","t.type");
        private final String table,type;Child(String table,String type){this.table=table;this.type=type;}
    }
    public void readAny(){if(!resources.isAdministrator() && resources.visibleProjectIds().stream().noneMatch(project->java.util.stream.Stream.of("1","2","3","4").anyMatch(type->allowed(project,type,"read"))))throw denied();}
    public List<Long> childIds(Child kind){
        readAny();var projects=resources.visibleProjectIds();if(projects.isEmpty() && !resources.isAdministrator())return List.of();
        String joins=" JOIN att_project p ON p.id=r.project_id AND p.del_flag='0' AND p.valid_flag='1' AND (r.project_code IS NULL OR r.project_code='' OR r.project_code=p.code)";
        if(kind==Child.RELATION)joins+=" JOIN dpp_etl_task t ON t.id=r.task_id AND t.code=r.task_code AND t.project_id=r.project_id AND t.del_flag='0'";
        if(kind==Child.RELATION_LOG)joins+=" JOIN dpp_etl_task_log t ON t.code=r.task_code AND t.version=r.task_version AND t.project_id=r.project_id AND t.project_code=r.project_code AND t.del_flag='0'";
        if(kind==Child.NODE_INSTANCE)joins+=" JOIN dpp_etl_task_instance i ON i.id=r.task_instance_id AND i.project_id=r.project_id AND i.del_flag='0' JOIN dpp_etl_task t ON t.id=i.task_id AND t.code=i.task_code AND t.project_id=i.project_id AND t.del_flag='0'";
        String scope=resources.isAdministrator()?"":" AND r.project_id IN ("+marks(projects.size())+")";
        var rows=jdbc.queryForList("SELECT r.id,r.project_id,"+kind.type+" task_type FROM "+kind.table+" r"+joins+" WHERE r.del_flag='0'"+scope,resources.isAdministrator()?new Object[0]:projects.toArray());
        Map<String,Boolean> grants=new HashMap<>();
        return rows.stream().filter(row->grants.computeIfAbsent(row.get("project_id")+":"+row.get("task_type"),key->allowed(number(row.get("project_id")),Objects.toString(row.get("task_type"),""),"read"))).map(row->number(row.get("id"))).toList();
    }
    public void child(Child kind,Long id){if(id==null || !childIds(kind).contains(id))throw denied();}
    public void requireVersionedTaskWrite(){throw new AccessDeniedException("请通过任务画布保存新版本；节点、依赖和执行历史不支持独立增删改");}
    public boolean allowed(Long project,String type,String action){
        if(resources.isAdministrator())return true;
        if(!Set.of("1","2","3","4").contains(type))return false;
        String prefix=(type.equals("3")||type.equals("4"))?"dpp:developTask":"dpp:integratioTask";
        if(action.equals("read"))return any(project,prefix+":list",prefix+":query",prefix+":info",prefix+":instance:list","dpp:etlTask:list","dpp:etlTask:query") || (type.equals("1")||type.equals("2")) && permissions.has(project,"dpp:integratioInstance:list");
        if(action.equals("execute"))return any(project,prefix+":once","dpp:etlTask:execute") || type.equals("1") && permissions.has(project,"dpp: integratioTask:once");
        return any(project,prefix+":"+action,"dpp:etlTask:"+action);
    }
    private void operation(Long project,String type,String action){if(project==null || !resources.visibleProjectIds().contains(project) || !allowed(project,type,action))throw new AccessDeniedException("当前账号没有此项目内该任务的操作权限");}
    private boolean any(Long project,String... capabilities){return permissions.any(project,capabilities);}
    public Map<String,Object> capabilities(Long project,String code,String type){
        project(project,code);readType(type,project);
        String actual=type==null?"1":type;
        String category=(actual.equals("3")||actual.equals("4"))?"att:dataDevCat:":"att:taskCat:";
        return Map.of("canRead",allowed(project,actual,"read"),"canAdd",allowed(project,actual,"add"),"canEdit",allowed(project,actual,"edit"),"canExecute",allowed(project,actual,"execute"),"canRemove",allowed(project,actual,"remove"),"categoryAdd",permissions.has(project,category+"add"),"categoryEdit",permissions.has(project,category+"edit"),"categoryRemove",permissions.has(project,category+"remove"),"canUseDolphin",!"QUARTZ".equals(jdbc.queryForObject("SELECT scheduler_type FROM att_project WHERE id=?",String.class,project)));
    }
    public void present(java.util.List<tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlTaskRespVO> rows){
        Map<String,Map<String,Object>> cached=new HashMap<>();
        for(var row:rows){
            if(row.getProjectId()==null || row.getType()==null){var parent=task(row.getId());row.setProjectId(number(parent.get("project_id")));row.setType(Objects.toString(parent.get("type")));}
            var flags=cached.computeIfAbsent(row.getProjectId()+":"+row.getType(),key->capabilities(row.getProjectId(),row.getProjectCode(),row.getType()));
            row.setCanEdit(Boolean.TRUE.equals(flags.get("canEdit")));row.setCanExecute(Boolean.TRUE.equals(flags.get("canExecute")));row.setCanRemove(Boolean.TRUE.equals(flags.get("canRemove")));
        }
    }
    private static String marks(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private static long number(Object value){if(value instanceof Number n)return n.longValue();return parse(Objects.toString(value,""));}
    public static long parse(String value){if(value==null || !value.matches("[0-9]+"))throw denied();try{return Long.parseLong(value);}catch(NumberFormatException error){throw denied();}}
    private static AccessDeniedException denied(){return new AccessDeniedException("开发资源不存在或不属于当前可访问项目");}
}
