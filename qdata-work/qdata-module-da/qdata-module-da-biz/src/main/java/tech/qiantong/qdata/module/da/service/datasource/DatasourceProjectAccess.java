package tech.qiantong.qdata.module.da.service.datasource;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.da.controller.admin.datasource.vo.DaDatasourceSaveReqVO;
import tech.qiantong.qdata.module.da.dal.dataobject.datasource.DaDatasourceProjectRelDO;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;

/** Connection mutations affect every project to which the connection is assigned. */
@Service
@RequiredArgsConstructor
public class DatasourceProjectAccess {
    private final JdbcTemplate jdbc;
    private final DataResourceAccess data;
    private final ProjectPermissionService permissions;
    private String permission(String action){return action.contains(":")?action:"da:dataSource:"+action;}
    private AccessDeniedException denied(){return new AccessDeniedException("当前项目没有此数据连接的操作权限");}
    public List<Long> projects(String action){return data.visibleProjectIds().stream().filter(p->permissions.has(p,permission(action))).sorted().toList();}
    public Set<Long> assigned(Long id){return new LinkedHashSet<>(jdbc.queryForList("SELECT DISTINCT project_id FROM da_datasource_project_rel WHERE datasource_id=? AND valid_flag='1'",Long.class,id));}
    private Map<String,Object> source(Long id){if(id==null)throw denied();var rows=jdbc.queryForList("SELECT id,creator_id,valid_flag FROM da_datasource WHERE id=? AND del_flag='0'",id);if(rows.size()!=1)throw denied();return rows.get(0);}
    private boolean allowed(Map<String,Object> source,String action,boolean all){
        if(permissions.isAdministrator())return true;
        var projects=assigned(((Number)source.get("id")).longValue());
        if(projects.isEmpty())return Objects.equals(number(source.get("creator_id")),SecurityUtils.getUserId()) && permissions.has(null,permission(action));
        var eligible=new HashSet<>(projects(action));return all?eligible.containsAll(projects):projects.stream().anyMatch(eligible::contains);
    }
    public void read(Long id,String action){if(!allowed(source(id),action,false))throw denied();}
    public void manage(Long id,String action){if(!allowed(source(id),action,true))throw denied();}
    public boolean canManage(Long id,String action){return allowed(source(id),action,true);}
    public void execute(Long id,String action){read(id,action);data.requireDatasource(id);}
    private Map<String,Object> connection(Long id){return jdbc.queryForMap("SELECT datasource_type,datasource_config FROM da_datasource WHERE id=? AND del_flag='0'",id);}
    private String hiveDatabase(Long id){var row=connection(id);return "Hive".equalsIgnoreCase(Objects.toString(row.get("datasource_type")))?com.alibaba.fastjson2.JSON.parseObject(Objects.toString(row.get("datasource_config"))).getString("dbname"):null;}
    public void tableScope(Long id,String table,String action){execute(id,action);var owners=data.owningHiveProjects(id,table);if(!owners.isEmpty() && owners.stream().noneMatch(projects(action)::contains))throw denied();}
    public void databaseScope(Long id,String database,String action){execute(id,action);String fallback=hiveDatabase(id);if(fallback==null)return;String name=database==null || database.isBlank()?fallback:database;tableScope(id,tech.qiantong.qdata.common.database.core.HiveTableIdentity.identifier(name)+".__qdata_authorization__",action);}
    public boolean databaseAllowed(Long id,String database,String action){try{databaseScope(id,database,action);return true;}catch(AccessDeniedException denied){return false;}}
    public void queryScope(Long id,String sql,String action){execute(id,action);String database=hiveDatabase(id);if(database==null)return;for(String table:tech.qiantong.qdata.common.database.core.HiveReadQuery.tables(sql,database))tableScope(id,table,action);}
    public void encodedQueryScope(Long id,String encoded,String action){execute(id,action);if(hiveDatabase(id)==null)return;try{queryScope(id,tech.qiantong.qdata.common.utils.AesEncryptUtil.desEncrypt(encoded).trim(),action);}catch(AccessDeniedException denied){throw denied;}catch(Exception invalid){throw new IllegalArgumentException("查询编码或Hive来源无效");}}
    public List<Long> visible(String action,boolean enabledOnly,String projectCode){
        Long project=null;if(projectCode!=null && !projectCode.isBlank()){
            var ids=jdbc.queryForList("SELECT id FROM att_project WHERE code=? AND valid_flag='1' AND del_flag='0'",Long.class,projectCode);if(ids.size()!=1 || !projects(action).contains(ids.get(0)))return List.of();project=ids.get(0);
        }
        List<Long> result=new ArrayList<>();
        for(var row:jdbc.queryForList("SELECT id,creator_id,valid_flag FROM da_datasource WHERE del_flag='0'"+(enabledOnly?" AND valid_flag='1'":""))){Long id=number(row.get("id"));if(allowed(row,action,false) && (project==null || assigned(id).contains(project)))result.add(id);}
        return result;
    }
    public Map<String,Boolean> capabilities(Long id){var row=source(id);return Map.of("query",allowed(row,"query",false),"edit",allowed(row,"edit",true),"remove",allowed(row,"remove",true),"test",allowed(row,"query",false) && "1".equals(Objects.toString(row.get("valid_flag"))));}
    public List<Map<String,Object>> visibleProjects(){var ids=projects("list");if(ids.isEmpty())return List.of();var rows=jdbc.queryForList("SELECT p.id,p.name,p.code,p.manager_id managerId,u.nick_name nickName FROM att_project p LEFT JOIN system_user u ON u.user_id=p.manager_id WHERE p.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY p.id",ids.toArray());var add=new HashSet<>(projects("add"));rows.forEach(row->row.put("canAdd",add.contains(number(row.get("id")))));return rows;}
    public List<Map<String,Object>> assignmentOptions(Long sourceId){
        Set<Long> ids=new LinkedHashSet<>(projects("add"));Map<Long,Boolean> fixed=new HashMap<>();
        if(sourceId!=null){manage(sourceId,"edit");for(var rel:bindings(sourceId)){ids.add(rel.getProjectId());fixed.put(rel.getProjectId(),rel.getDppAssigned());}}
        if(ids.isEmpty())return List.of();var result=jdbc.queryForList("SELECT p.id,p.name,p.code,p.manager_id managerId,u.nick_name nickName FROM att_project p LEFT JOIN system_user u ON u.user_id=p.manager_id WHERE p.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY p.id",ids.toArray());
        result.forEach(row->row.put("dppAssigned",fixed.getOrDefault(number(row.get("id")),false)));return result;
    }
    public List<DaDatasourceProjectRelDO> bindings(Long id){
        List<DaDatasourceProjectRelDO> result=new ArrayList<>();
        for(var row:jdbc.queryForList("SELECT r.id,r.project_id,p.code,r.dpp_assigned FROM da_datasource_project_rel r JOIN att_project p ON p.id=r.project_id WHERE r.datasource_id=? AND r.valid_flag='1'",id)){
            var rel=new DaDatasourceProjectRelDO();rel.setId(number(row.get("id")));rel.setDatasourceId(id);rel.setProjectId(number(row.get("project_id")));rel.setProjectCode(Objects.toString(row.get("code"),null));rel.setValidFlag(true);rel.setDppAssigned("1".equals(Objects.toString(row.get("dpp_assigned"))) || Boolean.TRUE.equals(row.get("dpp_assigned")));result.add(rel);
        }
        return result;
    }
    public void validateSave(DaDatasourceSaveReqVO request,boolean create,String action){
        Set<Long> before=Set.of();List<DaDatasourceProjectRelDO> existing=List.of();
        if(!create){manage(request.getId(),action);manage(request.getId(),"edit");before=assigned(request.getId());existing=bindings(request.getId());if(request.getProjectList()==null)request.setProjectList(existing);}
        var requested=request.getProjectList();if(requested==null || requested.isEmpty())throw new IllegalArgumentException("数据连接必须归属至少一个可管理项目");
        var addProjects=new HashSet<>(projects("add"));var actionProjects=new HashSet<>(projects(action));Set<Long> seen=new HashSet<>();List<DaDatasourceProjectRelDO> normalized=new ArrayList<>();
        for(var rel:requested){
            if(rel==null || rel.getProjectId()==null || !seen.add(rel.getProjectId()))throw new IllegalArgumentException("请选择不重复的有效项目");
            Long project=rel.getProjectId();if((create || !before.contains(project)) && !addProjects.contains(project))throw denied();if(create && !actionProjects.contains(project))throw denied();
            var codes=jdbc.queryForList("SELECT code FROM att_project WHERE id=? AND valid_flag='1' AND del_flag='0'",String.class,project);if(codes.size()!=1)throw denied();
            if(rel.getProjectCode()!=null && !rel.getProjectCode().isBlank() && !rel.getProjectCode().equals(codes.get(0)))throw new IllegalArgumentException("项目ID与项目编码不一致，请重新选择所属项目");
            var saved=new DaDatasourceProjectRelDO();saved.setProjectId(project);saved.setProjectCode(codes.get(0));saved.setDatasourceId(create?null:request.getId());saved.setValidFlag(true);
            saved.setDppAssigned(existing.stream().filter(x->project.equals(x.getProjectId())).anyMatch(x->Boolean.TRUE.equals(x.getDppAssigned())));normalized.add(saved);
        }
        request.setProjectList(normalized);request.setProjectListOld(new ArrayList<>(before));
        if(create){request.setId(null);request.setListCount(0L);request.setSyncCount(0L);request.setDataSize(0L);}
        else{
            var row=jdbc.queryForMap("SELECT creator_id,create_by,create_time,list_count,sync_count,data_size FROM da_datasource WHERE id=?",request.getId());request.setCreatorId(number(row.get("creator_id")));request.setCreateBy(Objects.toString(row.get("create_by"),null));
            Object date=row.get("create_time");if(date instanceof java.util.Date value)request.setCreateTime(value);else if(date instanceof java.time.LocalDateTime value)request.setCreateTime(java.sql.Timestamp.valueOf(value));
            request.setListCount(number(row.get("list_count")));request.setSyncCount(number(row.get("sync_count")));request.setDataSize(number(row.get("data_size")));
        }
    }
    public String resolveProjectCode(Long projectId, String projectCode) {
        if (projectId == null) return projectCode;
        if (!projects("list").contains(projectId)) throw denied();
        var codes = jdbc.queryForList("SELECT code FROM att_project WHERE id=? AND valid_flag='1' AND del_flag='0'", String.class, projectId);
        if (codes.size() != 1 || projectCode != null && !projectCode.isBlank() && !projectCode.equals(codes.get(0))) throw denied();
        return codes.get(0);
    }
    public Map<String,Object> relation(Long id,boolean write){
        if(id==null)throw denied();var rows=jdbc.queryForList("SELECT * FROM da_datasource_project_rel WHERE id=?",id);if(rows.size()!=1)throw denied();var row=rows.get(0);
        Long source=number(row.get("datasource_id")),project=number(row.get("project_id"));
        if(!projects(write?"edit":"query").contains(project))throw denied();if(write)manage(source,"edit");else read(source,"query");return row;
    }
    public void validateRelation(tech.qiantong.qdata.module.da.controller.admin.datasource.vo.DaDatasourceProjectRelSaveReqVO request,boolean create){
        if(create){manage(request.getDatasourceId(),"edit");if(!projects("add").contains(request.getProjectId()))throw denied();request.setId(null);request.setDppAssigned(false);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM da_datasource_project_rel WHERE datasource_id=? AND project_id=? AND valid_flag='1'",Long.class,request.getDatasourceId(),request.getProjectId())>0)throw new IllegalArgumentException("此连接已经分配到该项目");
        }else{
            var old=relation(request.getId(),true);if(!Objects.equals(number(old.get("datasource_id")),request.getDatasourceId()) || !Objects.equals(number(old.get("project_id")),request.getProjectId()))throw denied();
            request.setDppAssigned("1".equals(Objects.toString(old.get("dpp_assigned"))) || Boolean.TRUE.equals(old.get("dpp_assigned")));
            request.setCreatorId(number(old.get("creator_id")));request.setCreateBy(Objects.toString(old.get("create_by"),null));Object date=old.get("create_time");if(date instanceof java.util.Date value)request.setCreateTime(value);else if(date instanceof java.time.LocalDateTime value)request.setCreateTime(java.sql.Timestamp.valueOf(value));
        }
        var codes=jdbc.queryForList("SELECT code FROM att_project WHERE id=? AND valid_flag='1' AND del_flag='0'",String.class,request.getProjectId());if(codes.size()!=1)throw denied();request.setProjectCode(codes.get(0));
    }
    private static Long number(Object value){return value==null?null:Long.valueOf(String.valueOf(value));}
}
