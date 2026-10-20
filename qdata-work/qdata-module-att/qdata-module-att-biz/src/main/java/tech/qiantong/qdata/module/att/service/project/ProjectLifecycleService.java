package tech.qiantong.qdata.module.att.service.project;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.qiantong.qdata.api.ds.api.project.*;
import tech.qiantong.qdata.api.ds.api.service.project.IDsProjectService;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.httpClient.DsRequestUtils;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.att.controller.admin.project.vo.AttProjectSaveReqVO;
import tech.qiantong.qdata.redis.service.IRedisService;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.sql.Statement;
import java.util.*;

/** Project membership and roles commit together; external project ownership is explicit. */
@Service
@RequiredArgsConstructor
@Transactional(rollbackFor=Exception.class)
public class ProjectLifecycleService {
    private final JdbcTemplate jdbc;
    private final IRedisService sequences;
    private final IDsProjectService external;
    private final ProjectPermissionService permissions;
    private final ProjectAuthority authority;
    @Value("${ds.project_scheduler:QUARTZ}")
    private String defaultScheduler="QUARTZ";

    public long create(AttProjectSaveReqVO request) {
        if(!permissions.has(null,"att:project:add"))throw new AccessDeniedException("新建项目需要平台项目创建权限");
        if(request.getId()!=null)throw new IllegalArgumentException("新增项目不能指定历史标识");
        validate(request);
        String scheduler=request.getSchedulerType()==null || request.getSchedulerType().isBlank()
                ? normalizeDefault(defaultScheduler) : normalize(request.getSchedulerType());
        String code;
        if("QUARTZ".equals(scheduler)) code=String.valueOf(sequences.nextSequence("qdata:project:code",Math.multiplyExact(System.currentTimeMillis(),1000L)));
        else {
            DsRequestUtils.validateConfiguration();
            var input=new DsProjectCreateReqDTO();input.setProjectName(request.getName());input.setDescription(request.getDescription());
            var response=external.saveProject(input);
            if(response==null || !Objects.equals(response.getCode(),0) || response.getData()==null || response.getData().getCode()==null)throw new ServiceException("海豚调度项目创建未确认成功，请核对调度端项目名称、访问权限和创建结果后重试，或改用本地调度（Quartz）",400);
            code=String.valueOf(response.getData().getCode());
        }
        if(jdbc.queryForObject("SELECT COUNT(*) FROM att_project WHERE code=?",Long.class,code)>0)throw new IllegalStateException("项目编码已存在，请核对创建结果");
        try {
            long id=insert("INSERT INTO att_project(name,code,manager_id,description,remark,scheduler_type,valid_flag,del_flag,creator_id,create_by,create_time,update_time) VALUES(?,?,?,?,?,?,?,'0',?,?,?,?)",
                    request.getName(),code,request.getManagerId(),request.getDescription(),request.getRemark(),scheduler,Boolean.FALSE.equals(request.getValidFlag())?"0":"1",SecurityUtils.getUserId(),SecurityUtils.getUsername(),now(),now());
            bootstrap(id,request.getManagerId());
            return id;
        } catch(RuntimeException failure) {
            if("DOLPHINSCHEDULER".equals(scheduler))throw new IllegalStateException("外部调度项目 "+code+" 已创建，本地保存失败；保留外部项目，请核对后重试",failure);
            throw failure;
        }
    }
    public int update(AttProjectSaveReqVO request) {
        authority.edit(request);
        var current=jdbc.queryForMap("SELECT * FROM att_project WHERE id=? AND del_flag='0' FOR UPDATE",request.getId());
        if(request.getManagerId()==null)request.setManagerId(number(current.get("manager_id")));
        validate(request);
        String scheduler=normalize(Objects.toString(current.get("scheduler_type"),"DOLPHINSCHEDULER"));
        if(request.getSchedulerType()!=null && !scheduler.equals(normalize(request.getSchedulerType())))
            throw new IllegalArgumentException("项目调度器创建后不可修改");
        int count=jdbc.update("UPDATE att_project SET name=?,manager_id=?,description=?,remark=?,update_by=?,updater_id=?,update_time=? WHERE id=? AND del_flag='0'",request.getName(),request.getManagerId(),request.getDescription(),request.getRemark(),SecurityUtils.getUsername(),SecurityUtils.getUserId(),now(),request.getId());
        if(count!=1)throw new IllegalStateException("项目已变化，请刷新后重试");
        ensureManager(request.getId(),request.getManagerId());
        if("DOLPHINSCHEDULER".equals(scheduler)) {
            var input=new DsProjectUpdateReqDTO();input.setProjectCode(Long.valueOf(Objects.toString(current.get("code"))));input.setProjectName(request.getName());input.setDescription(request.getDescription());
            var response=external.updateProject(input);
            if(response==null || response.getCode()!=0)throw new IllegalStateException("外部调度项目更新未确认成功，本地修改已回滚；请核对外部状态");
        }
        return count;
    }
    private void validate(AttProjectSaveReqVO request) {
        if(request.getName()==null || request.getName().isBlank() || request.getName().length()>256)throw new IllegalArgumentException("项目名称不能为空或超过256字符");
        request.setName(request.getName().trim());
        if(request.getManagerId()==null)request.setManagerId(SecurityUtils.getUserId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM system_user WHERE user_id=? AND status='0' AND del_flag='0'",Long.class,request.getManagerId())!=1)throw new IllegalArgumentException("请选择启用的项目负责人");
    }
    private void bootstrap(Long project,Long manager) {
        var templates=jdbc.queryForList("SELECT * FROM system_role WHERE project_id=-1 AND status='0' AND del_flag='0'");
        for(var template:templates) {
            String key=Objects.toString(template.get("role_key"),"");
            if(!key.matches("[A-Za-z0-9_:-]+") || "admin".equalsIgnoreCase(key))throw new IllegalStateException("项目角色模板含保留标识，请先修正模板");
            long role=insert("INSERT INTO system_role(project_id,role_name,role_key,role_sort,data_scope,menu_check_strictly,dept_check_strictly,status,del_flag,create_by,create_time,update_time) VALUES(?,?,?,?,?,?,?,'0','0',?,?,?)",project,template.get("role_name"),key,template.get("role_sort"),template.get("data_scope"),template.get("menu_check_strictly"),template.get("dept_check_strictly"),SecurityUtils.getUsername(),now(),now());
            Set<Long> menuIds=new HashSet<>();
            for(var menu:jdbc.queryForList("SELECT m.menu_id,m.perms FROM system_menu m JOIN system_role_menu rm ON rm.menu_id=m.menu_id WHERE rm.role_id=? AND m.status='0'",template.get("role_id"))) {
                if(!safe(Objects.toString(menu.get("perms"),"")))throw new IllegalStateException("项目角色模板不能包含全局系统权限或通配符");
                menuIds.add(number(menu.get("menu_id")));
            }
            grantMenus(project,role,menuIds);
        }
        ensureManager(project,manager);
    }
    private void ensureManager(Long project,Long manager) {
        var roles=jdbc.queryForList("SELECT role_id FROM system_role WHERE project_id=? AND role_key='gly' AND status='0' AND del_flag='0'",Long.class,project);
        long role;
        if(roles.isEmpty()) {
            role=insert("INSERT INTO system_role(project_id,role_name,role_key,role_sort,data_scope,menu_check_strictly,dept_check_strictly,status,del_flag,create_by,create_time,update_time) VALUES(?,'项目管理员','gly',1,'5',1,1,'0','0',?,?,?)",project,SecurityUtils.getUsername(),now(),now());
            Set<Long> menus=new HashSet<>(jdbc.queryForList("SELECT menu_id FROM system_menu WHERE status='0' AND (perms LIKE 'att:project:%' OR perms LIKE 'att:projectUserRel:%') AND perms<>'att:project:add'",Long.class));
            grantMenus(project,role,menus);
        } else if(roles.size()==1) role=roles.get(0);
        else throw new IllegalStateException("项目存在多个管理员角色，请先核对角色配置");
        var members=jdbc.queryForList("SELECT id FROM att_project_user_rel WHERE project_id=? AND user_id=? AND del_flag='0' FOR UPDATE",Long.class,project,manager);
        if(members.isEmpty())jdbc.update("INSERT INTO att_project_user_rel(project_id,user_id,valid_flag,del_flag,creator_id,create_by,create_time,update_time) VALUES(?,?,'1','0',?,?,?,?)",project,manager,SecurityUtils.getUserId(),SecurityUtils.getUsername(),now(),now());
        else jdbc.update("UPDATE att_project_user_rel SET valid_flag='1',update_by=?,updater_id=?,update_time=? WHERE id=?",SecurityUtils.getUsername(),SecurityUtils.getUserId(),now(),members.get(0));
        jdbc.update("INSERT INTO system_user_role(user_id,role_id) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM system_user_role WHERE user_id=? AND role_id=?)",manager,role,manager,role);
    }
    private void grantMenus(Long project,Long role,Set<Long> requested) {
        var rows=jdbc.queryForList("SELECT menu_id,parent_id,perms FROM system_menu WHERE status='0'");var byId=new HashMap<Long,Map<String,Object>>();rows.forEach(row->byId.put(number(row.get("menu_id")),row));
        var pending=new ArrayDeque<>(requested);
        while(!pending.isEmpty()) {
            var row=byId.get(pending.remove());if(row==null || !safe(Objects.toString(row.get("perms"),"")))throw new IllegalStateException("项目菜单模板包含越界权限");
            Long parent=number(row.get("parent_id"));if(parent!=null && parent>0 && requested.add(parent))pending.add(parent);
        }
        for(Long id:requested)jdbc.update("INSERT IGNORE INTO system_role_menu(role_id,menu_id,project_id) VALUES(?,?,?)",role,id,project);
    }
    private boolean safe(String raw) {
        for(String permission:raw.split(",")) {
            String value=permission.trim();
            if(value.contains("*") || value.startsWith("system:") || value.startsWith("monitor:") || value.startsWith("tool:") || value.startsWith("infra:") || "att:project:add".equals(value))return false;
        }
        return true;
    }
    private long insert(String sql,Object... args) {
        var key=new GeneratedKeyHolder();jdbc.update(connection->{var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;},key);
        if(key.getKey()==null)throw new IllegalStateException("项目关联保存未返回标识");return key.getKey().longValue();
    }
    private static String normalize(String value) {
        String normalized=value==null?"":value.trim().toUpperCase(Locale.ROOT);
        if(!Set.of("QUARTZ","DOLPHINSCHEDULER").contains(normalized))throw new IllegalArgumentException("项目调度器必须为 QUARTZ 或 DOLPHINSCHEDULER");return normalized;
    }
    private static String normalizeDefault(String value) {
        try { return normalize(value); }
        catch(IllegalArgumentException e) { throw new IllegalStateException("项目默认调度器配置无效",e); }
    }
    private static java.sql.Timestamp now(){return java.sql.Timestamp.from(java.time.Instant.now());}
    private static Long number(Object value){return value==null?null:((Number)value).longValue();}
}
