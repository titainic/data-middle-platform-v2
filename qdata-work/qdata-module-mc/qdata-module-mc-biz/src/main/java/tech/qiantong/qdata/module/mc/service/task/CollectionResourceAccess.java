package tech.qiantong.qdata.module.mc.service.task;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.mc.controller.admin.task.vo.*;
import java.util.*;

/** Collection configuration, results and historical metadata retain the source namespace scope. */
@Service
@RequiredArgsConstructor
public class CollectionResourceAccess {
    private final JdbcTemplate jdbc;
    private final DataResourceAccess resources;
    private final tech.qiantong.qdata.module.mc.service.metadata.MetadataResourceAccess metadata;
    private final CollectionProjectAccess projects;
    public enum Child {
        INSTANCE("mc_task_instance","id"), INSTANCE_LOG("mc_task_instance_log","task_instance_id"),
        SCOPE("mc_task_scope","id"), SCHEDULER("mc_task_scheduler","id");
        final String table,key;Child(String table,String key){this.table=table;this.key=key;}
    }
    public enum History {
        TABLE("mc_table_log"), COLUMN("mc_column_log"), RELATION("mc_table_column_rel_log");
        final String table;History(String table){this.table=table;}
    }
    public void read(){if(!resources.isAdministrator() && !any("mc:task:list","mc:task:query","mc:metadata:table:list","mc:metadata:table:query","mc:metadata:table:detail"))throw denied();}
    public List<Long> taskIds(){return taskIds("read");}
    public List<Long> taskIds(String action){
        read();List<Long> allowed=new ArrayList<>();
        for(var row:jdbc.queryForList("SELECT id,project_id,creator_id,datasource_id,collection_scope,table_allowlist FROM mc_task WHERE del_flag='0'")){
            Long id=number(row.get("id"));try{projects.task(row,action);configuration(number(row.get("project_id")),number(row.get("datasource_id")),Objects.toString(row.get("collection_scope"),""),databases(id),Objects.toString(row.get("table_allowlist"),""));allowed.add(id);}catch(AccessDeniedException denied){/* Exclude tasks spanning a foreign project or namespace. */}
        }
        return allowed;
    }
    public Map<String,Object> task(Long id,String action){
        if(id==null)throw denied();var rows=jdbc.queryForList("SELECT * FROM mc_task WHERE id=? AND del_flag='0'",id);if(rows.size()!=1)throw denied();var row=rows.get(0);
        projects.task(row,action);configuration(number(row.get("project_id")),number(row.get("datasource_id")),Objects.toString(row.get("collection_scope"),""),databases(id),Objects.toString(row.get("table_allowlist"),""));
        if("execute".equals(action))projects.source(number(row.get("project_id")),number(row.get("datasource_id")),true);
        return row;
    }
    public void existingRequest(McTaskSaveReqVO request,String action){
        var row=task(request.getId(),action);Long source=number(row.get("datasource_id")),project=number(row.get("project_id"));
        if(request.getDatasourceId()!=null && !request.getDatasourceId().equals(source) || request.getProjectId()!=null && !request.getProjectId().equals(project))throw denied();
        request.setDatasourceId(source);request.setProjectId(project);
        if(Set.of("release","schedule").contains(action)){
            if(!Set.of("0","1").contains(Objects.toString(request.getStatus(),"")))throw new IllegalArgumentException("状态只能为0或1");
            if("1".equals(request.getStatus()))projects.source(project,source,true);
        }
    }
    public void save(McTaskSaveReqVO request,boolean existing){
        Map<String,Object> old=existing?task(request.getId(),"edit"):Map.of();
        if(!existing && request.getId()!=null)throw denied();
        Long source=request.getDatasourceId()!=null?request.getDatasourceId():number(old.get("datasource_id"));
        Long project=existing?number(old.get("project_id")):projects.choose(request.getProjectId(),source);
        if(existing && (request.getProjectId()!=null && !request.getProjectId().equals(project) || !Objects.equals(source,number(old.get("datasource_id")))))throw denied();
        projects.project(project,existing?"edit":"add");projects.source(project,source,false);
        String scope=request.getCollectionScope()!=null?request.getCollectionScope():Objects.toString(old.get("collection_scope"),"");
        List<String> databases=request.getScopeSaveReqVOS()==null?(existing?databases(request.getId()):List.of()):request.getScopeSaveReqVOS().stream().map(McTaskScopeSaveReqVO::getDbName).toList();
        String tables=request.getTableAllowlist()!=null?request.getTableAllowlist():Objects.toString(old.get("table_allowlist"),"");configuration(project,source,scope,databases,tables);
        if(request.getScopeSaveReqVOS()!=null)for(var item:request.getScopeSaveReqVOS()){
            if(item.getTaskId()!=null && !Objects.equals(item.getTaskId(),request.getId()))throw denied();
            if(item.getId()!=null && (!existing || jdbc.queryForObject("SELECT COUNT(*) FROM mc_task_scope WHERE id=? AND task_id=? AND del_flag='0'",Long.class,item.getId(),request.getId())!=1))throw denied();
        }
        if(existing && request.getSourceSystemId()==null)request.setSourceSystemId(number(old.get("source_system_id")));
        projects.references(project,request);
        request.setProjectId(project);request.setDatasourceId(source);request.setCollectionScope(scope);request.setTableAllowlist(tables);
        if(existing){
            request.setCreatorId(number(old.get("creator_id")));request.setCreateBy((String)old.get("create_by"));request.setCreateTime(jdbc.queryForObject("SELECT create_time FROM mc_task WHERE id=?",java.sql.Timestamp.class,request.getId()));request.setStatus(Objects.toString(old.get("status"),"0"));
            var schedulers=jdbc.queryForList("SELECT scheduler,status FROM mc_task_scheduler WHERE task_id=? AND del_flag='0'",request.getId());
            if(schedulers.size()!=1)throw new IllegalArgumentException("采集任务调度配置不完整");var scheduler=schedulers.get(0);
            if(request.getScheduler()!=null && !request.getScheduler().equals(scheduler.get("scheduler")))throw new IllegalArgumentException("不能通过编辑更换任务调度器");
            request.setScheduler((String)scheduler.get("scheduler"));request.setSchedulerStatus(Objects.toString(scheduler.get("status"),"0"));
            if(request.getScopeSaveReqVOS()==null){List<McTaskScopeSaveReqVO> scopes=new ArrayList<>();for(var entry:jdbc.queryForList("SELECT id,db_name,schema_name FROM mc_task_scope WHERE task_id=? AND del_flag='0'",request.getId())){var item=new McTaskScopeSaveReqVO();item.setId(number(entry.get("id")));item.setDbName((String)entry.get("db_name"));item.setSchemaName((String)entry.get("schema_name"));scopes.add(item);}request.setScopeSaveReqVOS(scopes);}
        }else{
            request.setCreatorId(SecurityUtils.getUserId());request.setCreateBy(SecurityUtils.getUsername());request.setCreateTime(new java.util.Date());request.setStatus("0");request.setSchedulerStatus("0");if(request.getScheduler()==null)request.setScheduler("QUARTZ");
        }
        request.setUpdatorId(SecurityUtils.getUserId());request.setUpdateBy(SecurityUtils.getUsername());request.setUpdateTime(new java.util.Date());
    }
    public McTaskRespVO present(McTaskRespVO row){if(row!=null){row.setCapabilities(projects.capabilities(row.getProjectId()));if(row.getProjectId()!=null){var names=jdbc.queryForList("SELECT name FROM att_project WHERE id=?",String.class,row.getProjectId());row.setProjectName(names.isEmpty()?null:names.get(0));}}return row;}
    private List<String> databases(Long id){return jdbc.queryForList("SELECT db_name FROM mc_task_scope WHERE task_id=? AND del_flag='0'",String.class,id);}
    private void configuration(Long project,Long source,String scope,List<String> databases,String tables){
        if(source==null)throw denied();resources.requireDatasourceConfiguration(source);if(project!=null)projects.source(project,source,false);
        String type=jdbc.queryForObject("SELECT datasource_type FROM da_datasource WHERE id=? AND del_flag='0'",String.class,source);
        if("Hive".equalsIgnoreCase(type) && (!"1".equals(scope) || databases.isEmpty()))throw new AccessDeniedException("Hive采集需要明确选择当前项目授权的数据库");
        for(String database:databases){namespace(source,database);if(project!=null)projects.namespace(project,source,database);}
        if(tables!=null && !tables.isBlank())for(String table:tables.split("[\r\n,]+"))if(!table.isBlank()){
            resources.requireMetadataTable(source,table.trim());var owners=resources.owningHiveProjects(source,table.trim());if(project!=null && !owners.isEmpty() && (owners.size()!=1 || !owners.contains(project)))throw denied();
        }
    }
    public void source(Long id){read();resources.requireDatasource(id);if(projects.sourceProjects(id).stream().noneMatch(p->projects.allowed(p,"read") || projects.allowed(p,"add")))throw denied();}
    public List<McTaskScopeRespVO> visibleDatabases(Long source,List<McTaskScopeRespVO> rows){
        List<McTaskScopeRespVO> allowed=new ArrayList<>();for(var row:rows)try{namespace(source,row.getDbName());if(projects.history(source,row.getDbName()))allowed.add(row);}catch(AccessDeniedException denied){}return allowed;
    }
    public List<Long> childIds(Child kind){return childIds(kind,"read");}
    public List<Long> childIds(Child kind,String action){
        var tasks=taskIds(action);if(tasks.isEmpty())return List.of();
        String join=kind==Child.INSTANCE_LOG?" JOIN mc_task_instance i ON i.id=r.task_instance_id AND i.del_flag='0' AND (r.task_id IS NULL OR r.task_id=i.task_id)":"";
        String task=kind==Child.INSTANCE_LOG?"i.task_id":"r.task_id";
        return jdbc.queryForList("SELECT DISTINCT r."+kind.key+" FROM "+kind.table+" r"+join+" WHERE r.del_flag='0' AND "+task+" IN ("+marks(tasks.size())+")",Long.class,tasks.toArray());
    }
    public void child(Child kind,Long id){if(id==null || !childIds(kind).contains(id))throw denied();}
    public void instance(Long id){child(Child.INSTANCE,id);}
    public List<Long> historyIds(History kind){
        read();List<Long> result=new ArrayList<>();Set<Long> linkedTables=new HashSet<>(metadata.tableIds());Map<String,Boolean> namespaces=new HashMap<>();
        String declared=kind==History.RELATION?"NULL":"r.datasource_id";
        String database=kind==History.TABLE?"COALESCE(r.db_name,d.db_name)":"d.db_name";
        for(var row:jdbc.queryForList("SELECT r.id,r.table_id,"+declared+" declared_source,d.datasource_id database_source,COALESCE("+declared+",d.datasource_id) source_id,"+database+" db_name FROM "+kind.table+" r LEFT JOIN mc_db d ON d.id=r.db_id WHERE r.del_flag='0'")){
            Long source=number(row.get("source_id"));if(source==null)continue;
            if(row.get("declared_source")!=null && row.get("database_source")!=null && !Objects.equals(number(row.get("declared_source")),number(row.get("database_source"))))continue;
            if(linkedTables.contains(number(row.get("table_id")))){result.add(number(row.get("id")));continue;}
            String databaseName=Objects.toString(row.get("db_name"),"");String key=source+":"+databaseName;
            if(namespaces.computeIfAbsent(key,ignored->{try{namespace(source,databaseName);return projects.history(source,databaseName);}catch(AccessDeniedException denied){return false;}}))result.add(number(row.get("id")));
        }
        return result;
    }
    public void history(History kind,Long id){if(id==null || !historyIds(kind).contains(id))throw denied();}
    public void requireManagedWrite(){throw new AccessDeniedException("采集范围和调度请通过采集任务维护；执行与元数据历史由系统生成并保留，不支持独立增删改");}
    private void namespace(Long source,String database){if(database==null || database.isBlank())throw denied();resources.requireMetadataTable(source,database+".__metadata_scope");}
    private static Long number(Object value){return value==null?null:((Number)value).longValue();}
    private static String marks(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private boolean any(String... permissions){return Arrays.stream(permissions).anyMatch(SecurityUtils::hasPermi);}
    private static AccessDeniedException denied(){return new AccessDeniedException("采集或历史资源不存在，或不属于当前可访问范围");}
}
