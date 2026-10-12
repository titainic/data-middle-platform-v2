package tech.qiantong.qdata.module.att.service.project;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;

/** Project catalog grants never inherit write capabilities from another project. */
@Service
@RequiredArgsConstructor
public class ProjectCatalogAccess {
    private final JdbcTemplate jdbc;
    private final ProjectAuthority authority;
    private final ProjectPermissionService permissions;
    public enum Kind {
        DEVELOP("att_data_dev_cat","att:dataDevCat","dpp:developTask"),
        INTEGRATION("att_task_cat","att:taskCat","dpp:integratioTask");
        private final String table,prefix,taskPrefix;
        Kind(String table,String prefix,String taskPrefix){this.table=table;this.prefix=prefix;this.taskPrefix=taskPrefix;}
    }
    public record Identity(Long projectId,String projectCode,String code,Long parentId) {}
    private boolean capable(Kind kind,Long project,String action){
        if(action.equals("read"))return permissions.any(project,kind.prefix+":list",kind.prefix+":query",kind.taskPrefix+":list",kind.taskPrefix+":query",kind.taskPrefix+":info","dpp:etlTask:list","dpp:etlTask:query");
        if(action.equals("author"))return permissions.any(project,kind.prefix+":add",kind.prefix+":edit");
        return permissions.has(project,kind.prefix+":"+action);
    }
    public List<Long> projects(Kind kind,String action,Long id,String code){return projects(kind,action,id,code,false);}
    public List<Long> projects(Kind kind,String action,Long id,String code,boolean emptyWithoutCapability){
        if(id==null && code!=null && !code.isBlank()){
            var ids=jdbc.queryForList("SELECT id FROM att_project WHERE code=? AND del_flag='0'",Long.class,code);
            if(ids.size()!=1)throw denied();id=ids.get(0);
        }
        if(id!=null){
            if(emptyWithoutCapability){authority.read(id);String expected=jdbc.queryForObject("SELECT code FROM att_project WHERE id=?",String.class,id);if(code!=null && !code.isBlank() && !code.equals(expected))throw denied();return capable(kind,id,action)?List.of(id):List.of();}
            require(kind,id,code,action);return List.of(id);
        }
        return authority.projects().stream().filter(project->capable(kind,project,action)).toList();
    }
    private String require(Kind kind,Long project,String code,String action){
        authority.read(project);if(!capable(kind,project,action))throw denied();
        String expected=jdbc.queryForObject("SELECT code FROM att_project WHERE id=?",String.class,project);
        if(code!=null && !code.isBlank() && !code.equals(expected))throw denied();
        if(!action.equals("read") && !action.equals("export") && TransactionSynchronizationManager.isActualTransactionActive())jdbc.queryForObject("SELECT id FROM att_project WHERE id=? FOR UPDATE",Long.class,project);
        return expected;
    }
    private Map<String,Object> row(Kind kind,Long id){
        if(id==null || id<=0)throw denied();var rows=jdbc.queryForList("SELECT id,project_id,project_code,parent_id,code FROM "+kind.table+" WHERE id=? AND del_flag='0'",id);
        if(rows.size()!=1)throw denied();return rows.get(0);
    }
    public Identity category(Kind kind,Long id,String action){
        var value=row(kind,id);Long project=number(value.get("project_id"));String code=require(kind,project,Objects.toString(value.get("project_code"),null),action);
        return new Identity(project,code,Objects.toString(value.get("code"),null),number(value.get("parent_id")));
    }
    public Identity prepare(Kind kind,Long id,Long project,String projectCode,Long parent,String code,String name,String action){
        if(action.equals("edit") && id==null)throw new IllegalArgumentException("修改类目需要标识");
        String storedCode=null;
        if(id!=null){var old=row(kind,id);Long owner=number(old.get("project_id"));if(project!=null && !project.equals(owner))throw denied();project=owner;if(parent==null)parent=number(old.get("parent_id"));storedCode=Objects.toString(old.get("code"),null);if(code!=null && !code.isBlank() && !Objects.equals(code,storedCode))throw denied();}
        if(project==null){var eligible=projects(kind,action,null,projectCode);if(eligible.size()!=1)throw new AccessDeniedException("请选择有权限的类目所属项目");project=eligible.get(0);}
        if(parent==null)parent=0L;
        if(parent<0)throw new IllegalArgumentException("上级类目标识无效");
        String expected=require(kind,project,projectCode,action);
        if(name==null || name.isBlank())throw new IllegalArgumentException("类目名称不能为空");
        var seen=new HashSet<Long>();Long ancestor=parent;
        while(ancestor!=null && ancestor>0){
            if(Objects.equals(ancestor,id) || !seen.add(ancestor))throw new IllegalArgumentException("类目不能移动到自身或其后代下");
            var value=row(kind,ancestor);if(!Objects.equals(project,number(value.get("project_id"))))throw denied();ancestor=number(value.get("parent_id"));
        }
        if(used(kind,id,parent,name,project))throw new IllegalArgumentException("同一项目的该上级类目下已存在同名类目");
        return new Identity(project,expected,storedCode,parent);
    }
    private boolean used(Kind kind,Long id,Long parent,String name,Long project){
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+kind.table+" WHERE project_id=? AND COALESCE(parent_id,0)=? AND name=? AND del_flag='0' AND id<>?",Long.class,project,parent==null?0L:parent,name.trim(),id==null?-1L:id)>0;
    }
    public boolean nameUsed(Kind kind,Long id,Long parent,String name,Long project,String code){
        if(id!=null)project=category(kind,id,"author").projectId();
        if(parent!=null && parent>0){Long owner=category(kind,parent,"read").projectId();if(project!=null && !project.equals(owner))throw denied();project=owner;}
        if(project==null){var eligible=projects(kind,"author",null,code);if(eligible.size()!=1)throw new AccessDeniedException("请明确类目所属项目");project=eligible.get(0);}
        require(kind,project,code,"author");return name!=null && !name.isBlank() && used(kind,id,parent,name,project);
    }
    private static Long number(Object value){return value==null?null:((Number)value).longValue();}
    private static AccessDeniedException denied(){return new AccessDeniedException("类目或其上级不属于当前项目操作权限范围");}
}
