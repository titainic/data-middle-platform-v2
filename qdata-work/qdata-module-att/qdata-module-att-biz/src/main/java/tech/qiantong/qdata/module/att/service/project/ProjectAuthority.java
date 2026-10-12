package tech.qiantong.qdata.module.att.service.project;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tech.qiantong.qdata.common.core.domain.entity.SysRole;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.att.controller.admin.project.vo.*;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;

/** Project administration cannot grant membership or capabilities in another project. */
@Service
@RequiredArgsConstructor
public class ProjectAuthority {
    private final JdbcTemplate jdbc;
    private final ProjectPermissionService permissions;
    public List<Long> projects(){
        if(permissions.isAdministrator())return jdbc.queryForList("SELECT id FROM att_project WHERE del_flag='0'",Long.class);
        Long user=SecurityUtils.getUserId();return jdbc.queryForList("SELECT p.id FROM att_project p WHERE p.del_flag='0' AND (p.manager_id=? OR EXISTS(SELECT 1 FROM att_project_user_rel r WHERE r.project_id=p.id AND r.user_id=? AND r.del_flag='0' AND r.valid_flag='1'))",Long.class,user,user);
    }
    public Long resolve(Long project){if(project!=null){read(project);return project;}var ids=projects();if(ids.size()!=1)throw new AccessDeniedException("请明确选择当前可访问的项目");return ids.get(0);}
    public void read(Long project){if(project==null || !projects().contains(project))throw denied();}
    public void require(Long project,String... capabilities){read(project);if(!permissions.any(project,capabilities))throw denied();}
    public void manage(Long project,String... capabilities){
        require(project,capabilities);
        if(!permissions.isAdministrator()){
            Long user=SecurityUtils.getUserId();long managers=jdbc.queryForObject("SELECT COUNT(*) FROM att_project p WHERE p.id=? AND (p.manager_id=? OR EXISTS(SELECT 1 FROM system_user_role ur JOIN system_role r ON r.role_id=ur.role_id WHERE ur.user_id=? AND r.project_id=p.id AND r.role_key='gly' AND r.status='0' AND r.del_flag='0'))",Long.class,project,user,user);
            if(managers!=1)throw new AccessDeniedException("此操作需要当前项目的管理员权限");
        }
        if(TransactionSynchronizationManager.isActualTransactionActive())jdbc.queryForObject("SELECT id FROM att_project WHERE id=? FOR UPDATE",Long.class,project);
    }
    public boolean canEdit(Long project){
        if(!projects().contains(project) || !permissions.has(project,"att:project:edit"))return false;
        if(permissions.isAdministrator())return true;Long user=SecurityUtils.getUserId();
        return jdbc.queryForObject("SELECT COUNT(*) FROM att_project p WHERE p.id=? AND (p.manager_id=? OR EXISTS(SELECT 1 FROM system_user_role ur JOIN system_role r ON r.role_id=ur.role_id WHERE ur.user_id=? AND r.project_id=p.id AND r.role_key='gly' AND r.status='0' AND r.del_flag='0'))",Long.class,project,user,user)==1;
    }
    public void edit(AttProjectSaveReqVO request){
        manage(request.getId(),"att:project:edit");String code=jdbc.queryForObject("SELECT code FROM att_project WHERE id=?",String.class,request.getId());
        if(request.getCode()!=null && !request.getCode().equals(code))throw new AccessDeniedException("项目编码不能变更或指向另一个调度项目");request.setCode(code);request.setCreatorId(null);request.setCreateBy(null);request.setCreateTime(null);
    }
    public List<Long> members(){var projects=projects();if(projects.isEmpty())return List.of();return jdbc.queryForList("SELECT id FROM att_project_user_rel WHERE del_flag='0' AND project_id IN ("+marks(projects.size())+")",Long.class,projects.toArray());}
    public Map<String,Object> member(Long id){
        if(id==null)throw denied();var rows=jdbc.queryForList("SELECT id,project_id,user_id FROM att_project_user_rel WHERE id=? AND del_flag='0'",id);if(rows.size()!=1)throw denied();var row=rows.get(0);read(number(row.get("project_id")));return row;
    }
    public void memberRequest(AttProjectUserRelSaveReqVO request,boolean update,boolean withRoles){
        if(update){var old=member(request.getId());Long project=number(old.get("project_id")),user=number(old.get("user_id"));
            if(request.getProjectId()!=null && !request.getProjectId().equals(project) || request.getUserId()!=null && !request.getUserId().equals(user))throw denied();request.setProjectId(project);request.setUserId(user);
        }else if(request.getId()!=null)throw denied();
        manage(request.getProjectId(),update?"att:projectUserRel:edit":"att:projectUserRel:add",update?"att:projectUserRel:edit":"att:project:project:add");
        var users=withRoles && !update?request.getUserIdList():(request.getUserId()==null?List.<Long>of():List.of(request.getUserId()));users(users,false,request.getProjectId());
        if(update){request.setCreatorId(null);request.setCreateBy(null);request.setCreateTime(null);}
        if(withRoles){if(request.getRoleIdList()==null)request.setRoleIdList(List.of());for(Long id:request.getRoleIdList()){
            var role=role(id,false,"att:project:role:query","att:projectUserRel:add","att:project:project:add","att:projectUserRel:edit");if(!Objects.equals(number(role.get("project_id")),request.getProjectId()))throw denied();
        }}
    }
    public Long memberRemoval(Long[] ids){
        Set<Long> projects=new HashSet<>();for(Long id:ids)projects.add(number(member(id).get("project_id")));if(projects.size()!=1)throw new AccessDeniedException("成员批量操作必须属于同一个项目");Long project=projects.iterator().next();manage(project,"att:projectUserRel:remove");return project;
    }
    public Map<String,Object> role(Long id,boolean edit,String... capabilities){
        if(id==null)throw denied();var rows=jdbc.queryForList("SELECT role_id,project_id,role_key,status FROM system_role WHERE role_id=? AND del_flag='0'",id);if(rows.size()!=1)throw denied();var row=rows.get(0);Long project=number(row.get("project_id"));
        if(project==null || project<=0)throw new AccessDeniedException("全局角色与角色模板请通过系统角色管理维护");
        if(edit)manage(project,capabilities);else require(project,capabilities);return row;
    }
    public void roleIdentity(SysRole request,String capability){
        var old=role(request.getRoleId(),true,capability);Long project=number(old.get("project_id"));if(request.getProjectId()!=null && !request.getProjectId().equals(project))throw denied();request.setProjectId(project);
        if(request.getRoleKey()!=null){key(request.getRoleKey());if("gly".equals(old.get("role_key")) && !"gly".equals(request.getRoleKey()))throw new AccessDeniedException("内置项目管理员的权限标识不能修改");}
    }
    public void roleSave(SysRole request,boolean create){
        if(create){if(request.getRoleId()!=null && request.getRoleId()!=0)throw denied();manage(request.getProjectId(),"att:project:role:add");key(request.getRoleKey());}
        else roleIdentity(request,"att:project:role:edit");
        if(request.getMenuIds()==null)request.setMenuIds(new Long[0]);
        for(Long id:request.getMenuIds()){
            var rows=jdbc.queryForList("SELECT perms FROM system_menu WHERE menu_id=? AND status='0'",String.class,id);if(rows.size()!=1)throw denied();String raw=rows.get(0);if(raw==null || raw.isBlank())continue;
            for(String permission:raw.split(",")){
                String value=permission.trim();if(value.contains("*") || value.startsWith("system:") || value.startsWith("monitor:") || value.startsWith("tool:") || value.startsWith("infra:"))throw new AccessDeniedException("项目角色不能包含全局系统管理权限");
                if(!permissions.has(request.getProjectId(),value))throw new AccessDeniedException("不能授予超出当前项目授权范围的权限");
            }
        }
    }
    public void roleUsers(Long roleId,Long[] users,boolean removal){
        var role=role(roleId,true,"att:project:role:edit");Long project=number(role.get("project_id"));if(users==null || users.length==0)throw denied();users(Arrays.asList(users),true,project);
        if(removal && "gly".equals(role.get("role_key"))){
            var admins=jdbc.queryForList("SELECT DISTINCT ur.user_id FROM system_user_role ur JOIN system_role r ON r.role_id=ur.role_id JOIN att_project_user_rel m ON m.user_id=ur.user_id AND m.project_id=r.project_id AND m.del_flag='0' AND m.valid_flag='1' WHERE r.project_id=? AND r.role_key='gly' AND r.status='0' AND r.del_flag='0'",Long.class,project);
            if(!admins.isEmpty() && new HashSet<>(Arrays.asList(users)).containsAll(admins))throw new AccessDeniedException("项目至少需要保留一名项目管理员");
        }
    }
    public List<Long> projectMemberIds(Long project){read(project);return jdbc.queryForList("SELECT DISTINCT user_id FROM att_project_user_rel WHERE project_id=? AND del_flag='0' AND valid_flag='1'",Long.class,project);}
    private void users(List<Long> users,boolean mustBeMember,Long project){
        if(users==null || users.isEmpty() || users.size()>200 || users.stream().anyMatch(Objects::isNull))throw new AccessDeniedException("请选择有效的项目成员");Set<Long> unique=new HashSet<>(users);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM system_user WHERE user_id IN ("+marks(unique.size())+") AND status='0' AND del_flag='0'",Long.class,unique.toArray())!=unique.size())throw denied();
        if(mustBeMember)for(Long user:unique)if(jdbc.queryForObject("SELECT COUNT(*) FROM att_project_user_rel WHERE project_id=? AND user_id=? AND del_flag='0' AND valid_flag='1'",Long.class,project,user)<1)throw denied();
    }
    private void key(String key){if(key==null || !key.matches("[A-Za-z0-9_:-]+") || "admin".equalsIgnoreCase(key))throw new AccessDeniedException("项目角色不能使用全局管理员标识或通配符");}
    private static Long number(Object value){return value==null?null:((Number)value).longValue();}
    private static String marks(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private static AccessDeniedException denied(){return new AccessDeniedException("项目、成员或角色不属于当前授权范围");}
}
