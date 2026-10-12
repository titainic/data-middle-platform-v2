package tech.qiantong.qdata.module.att.service.client;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.att.controller.admin.client.vo.*;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;

/** Management authorization is separate from the internal OAuth client loader. */
@Service
@RequiredArgsConstructor
public class ClientResourceAccess implements tech.qiantong.qdata.module.att.api.client.ClientManagementAccess {
    private final JdbcTemplate jdbc;
    private final ProjectPermissionService permissions;

    @Override public void authorizeClient(Long id,String action){client(id,action);}
    public List<Long> projects() {
        if (permissions.isAdministrator()) return jdbc.queryForList("SELECT id FROM att_project WHERE del_flag='0' AND valid_flag='1'", Long.class);
        Long user = SecurityUtils.getUserId();
        return jdbc.queryForList("SELECT p.id FROM att_project p WHERE p.del_flag='0' AND p.valid_flag='1' AND (p.manager_id=? OR EXISTS(SELECT 1 FROM att_project_user_rel r WHERE r.project_id=p.id AND r.user_id=? AND r.del_flag='0' AND r.valid_flag='1'))", Long.class, user, user);
    }
    private String[] capabilities(String mode) {
        return switch (mode) {
            case "read" -> new String[]{"att:client:list", "att:client:query"};
            case "author" -> new String[]{"att:client:add", "att:client:edit"};
            default -> new String[]{"att:client:" + mode};
        };
    }
    public Long project(Long id, String mode) {
        var available = projects();
        if (id == null) {
            var eligible = available.stream().filter(p -> permissions.any(p, capabilities(mode))).toList();
            if (eligible.size() != 1) throw new AccessDeniedException("请选择有权限的应用所属项目");
            id = eligible.get(0);
        }
        if (!available.contains(id) || !permissions.any(id, capabilities(mode))) throw denied();
        return id;
    }
    public List<Map<String, Object>> projectOptions(boolean editable) {
        var result = new ArrayList<Map<String, Object>>();
        for (Long id : projects()) if (permissions.any(id, capabilities(editable ? "author" : "read")))
            result.add(jdbc.queryForMap("SELECT id,name FROM att_project WHERE id=?", id));
        return result;
    }
    private boolean allowed(Map<String,Object> row, String... capability) {
        if (permissions.isAdministrator()) return true;
        Long project = number(row.get("project_id"));
        if (project == null) return Objects.equals(number(row.get("creator_id")), SecurityUtils.getUserId()) && permissions.any(null, capability);
        return projects().contains(project) && permissions.any(project, capability);
    }
    public List<Long> clientIds(String mode) {
        var ids = new ArrayList<Long>();
        var visible = new HashSet<>(projects());
        var grants = new HashMap<Long,Boolean>();
        boolean admin = permissions.isAdministrator();
        for (var row : jdbc.queryForList("SELECT id,project_id,creator_id FROM att_client WHERE del_flag='0'")) {
            Long project = number(row.get("project_id"));
            boolean allowed = admin || (project == null ? Objects.equals(number(row.get("creator_id")), SecurityUtils.getUserId()) && permissions.any(null,capabilities(mode)) : visible.contains(project) && grants.computeIfAbsent(project, p -> permissions.any(p,capabilities(mode))));
            if (allowed) ids.add(number(row.get("id")));
        }
        return ids;
    }
    private Map<String,Object> row(String table, Long id, boolean deleted, boolean lock) {
        if (id == null) throw denied();
        var rows = jdbc.queryForList("SELECT * FROM " + table + " WHERE id=?" + (deleted ? "" : " AND del_flag='0'") + (lock && TransactionSynchronizationManager.isActualTransactionActive() ? " FOR UPDATE" : ""), id);
        if (rows.size() != 1) throw denied();
        return rows.get(0);
    }
    public Map<String,Object> client(Long id, String mode) {
        var row = row("att_client",id,false,!"read".equals(mode));
        if (!allowed(row,capabilities(mode))) throw denied();
        return row;
    }
    private Map<String,Object> api(Long id) {
        var row = row("ds_api",id,false,true);
        if (!allowed(row,"ds:api:edit")) throw denied();
        return row;
    }
    public void prepare(AttClientSaveReqVO value, String mode, boolean existing) {
        if (existing) {
            var old = client(value.getId(),mode); Long project = number(old.get("project_id"));
            if (project != null && value.getProjectId() != null && !project.equals(value.getProjectId())) throw denied();
            value.setProjectId(project != null ? project : project(value.getProjectId(),mode));
        } else {
            if (value.getId() != null) throw denied();
            value.setProjectId(project(value.getProjectId(),mode));
        }
        if (value.getPublicFlag() == null) value.setPublicFlag("0");
        if (!Set.of("0","1").contains(value.getPublicFlag())) throw new IllegalArgumentException("公开标识无效");
        value.setCreatorId(null);value.setCreateBy(null);value.setCreateTime(null);
        value.setUpdatorId(null);value.setUpdateBy(null);value.setUpdateTime(null);
    }
    public void removal(Long id) {
        client(id,"remove");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM att_client_api_rel WHERE client_id=? AND del_flag='0'",Long.class,id)>0)
            throw new IllegalArgumentException("应用仍有关联授权，请先撤销引用");
    }
    public AttClientRespVO present(AttClientRespVO value) {
        if (value == null) return null;
        value.setSecret(null);
        var row = row("att_client",value.getId(),false,false);
        value.setCanEdit(allowed(row,"att:client:edit"));
        value.setCanRemove(allowed(row,"att:client:remove"));
        if(value.getProjectId()!=null) {
            var names=jdbc.queryForList("SELECT name FROM att_project WHERE id=?",String.class,value.getProjectId());
            if(names.size()==1)value.setProjectName(names.get(0));
        }
        return value;
    }
    public List<Map<String,Object>> grantOptions(Long clientId) {
        client(clientId,"edit");var result=new ArrayList<Map<String,Object>>();
        for(var row:jdbc.queryForList("SELECT id,name,api_version apiVersion,req_method reqMethod,CONCAT('/services/',api_version,'/',TRIM(LEADING '/' FROM api_url)) apiUrl,project_id,creator_id FROM ds_api WHERE del_flag='0'"))
            if(allowed(row,"ds:api:edit")) { row.remove("creator_id");result.add(row); }
        return result;
    }
    public void grant(AttClientApiRelSaveReqVO value, boolean existing) {
        Map<String,Object> old = existing ? row("att_client_api_rel",value.getId(),false,false) : null;
        if (old != null) {
            Long client=number(old.get("client_id")),api=number(old.get("api_id"));
            if(value.getClientId()!=null && !client.equals(value.getClientId()) || value.getApiId()!=null && !api.equals(value.getApiId()))throw denied();
            value.setClientId(client);value.setApiId(api);
        } else if(value.getId()!=null) throw denied();
        client(value.getClientId(),"edit");api(value.getApiId());
        if(existing) row("att_client_api_rel",value.getId(),false,true);
        if(value.getPvFlag()==null && old!=null)value.setPvFlag(Objects.toString(old.get("pv_flag"),null));
        if(value.getStatus()==null)value.setStatus(old==null?"1":Objects.toString(old.get("status"),null));
        if(value.getStartTime()==null && old!=null)value.setStartTime((Date)old.get("start_time"));
        if(value.getEndTime()==null && old!=null)value.setEndTime((Date)old.get("end_time"));
        if(!Set.of("0","1").contains(Objects.toString(value.getPvFlag(),"")) || !Set.of("0","1").contains(Objects.toString(value.getStatus(),"")))throw new IllegalArgumentException("授权状态和有效期类型无效");
        if("0".equals(value.getPvFlag()) && (value.getStartTime()==null || value.getEndTime()==null || value.getEndTime().before(value.getStartTime())))throw new IllegalArgumentException("授权开始和结束日期无效");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM att_client_api_rel WHERE client_id=? AND api_id=? AND del_flag='0' AND id<>?",Long.class,value.getClientId(),value.getApiId(),value.getId()==null?-1L:value.getId())>0)throw new IllegalArgumentException("应用已存在该 API 授权，请编辑既有授权");
        value.setCreatorId(null);value.setCreateBy(null);value.setCreateTime(null);
        value.setUpdatorId(null);value.setUpdateBy(null);value.setUpdateTime(null);
    }
    public void revoke(Long id) {
        var relation=row("att_client_api_rel",id,false,false);
        var client=row("att_client",number(relation.get("client_id")),true,true);
        var api=row("ds_api",number(relation.get("api_id")),true,true);
        if(!allowed(client,"att:client:edit") && !allowed(api,"ds:api:edit"))throw denied();
        row("att_client_api_rel",id,false,true);
    }
    public AttClientApiRelRespVO present(AttClientApiRelRespVO value) {
        var client=row("att_client",value.getClientId(),true,false);
        var api=row("ds_api",value.getApiId(),true,false);
        boolean appEdit=allowed(client,"att:client:edit"),apiEdit=allowed(api,"ds:api:edit");
        value.setCanEdit(appEdit && apiEdit);value.setCanRevoke(appEdit || apiEdit);return value;
    }
    private static Long number(Object value){return value==null?null:((Number)value).longValue();}
    private static AccessDeniedException denied(){return new AccessDeniedException("应用或 API 授权不属于当前项目权限范围");}
}
