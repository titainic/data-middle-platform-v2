package tech.qiantong.qdata.module.dp.architecture;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.dp.warehouse.WarehouseDefinition;
import tech.qiantong.qdata.module.dp.warehouse.WarehouseService;
import tech.qiantong.qdata.module.dpp.api.service.DevelopmentManagementAccess;
import tech.qiantong.qdata.module.ds.api.service.ServiceManagementAccess;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;

/** A drawing never grants access to a referenced platform resource. */
@Service
@RequiredArgsConstructor
public class ArchitectureResourceAccess {
    private final JdbcTemplate jdbc;
    private final DataResourceAccess data;
    private final ProjectPermissionService permissions;
    private final WarehouseService warehouse;
    private final DevelopmentManagementAccess tasks;
    private final ServiceManagementAccess apis;

    public void project(Long id,String action) {
        if(id==null || !data.visibleProjectIds().contains(id) || !permissions.has(id,"dp:model:"+action))throw denied();
    }
    public boolean canEdit(Long id) {
        return id!=null && data.visibleProjectIds().contains(id) && permissions.has(id,"dp:model:edit") && permissions.has(id,"dp:model:query");
    }
    public List<Map<String,Object>> projects() {
        List<Map<String,Object>> result=new ArrayList<>();
        for(Long id:data.visibleProjectIds())if(permissions.has(id,"dp:model:list")){
            var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT id,name FROM att_project WHERE id=?",id));
            row.put("canEdit",canEdit(id));row.put("canRead",permissions.has(id,"dp:model:query"));result.add(row);
        }
        return result;
    }
    public List<Map<String,Object>> documents() {
        var projects=projects().stream().map(p->number(p.get("id"))).toList();
        List<Object> args=new ArrayList<>(projects);
        String scope=projects.isEmpty()?"1=0":"project_id IN ("+marks(projects.size())+")";
        String legacy=permissions.isAdministrator()?"project_id IS NULL":"project_id IS NULL AND creator_id=?";
        if(!permissions.isAdministrator())args.add(SecurityUtils.getUserId());
        var rows=jdbc.queryForList("SELECT id,name,project_id,revision,update_time FROM dp_architecture_document WHERE ("+scope+") OR ("+legacy+") ORDER BY update_time DESC,id DESC",args.toArray());
        rows.forEach(row->row.put("canEdit",canEdit(number(row.get("project_id")))));return rows;
    }
    public Map<String,Object> document(Long id,String action) {
        if(id==null)throw denied();var rows=jdbc.queryForList("SELECT * FROM dp_architecture_document WHERE id=?",id);
        if(rows.size()!=1)throw denied();var row=rows.get(0);Long project=number(row.get("project_id"));
        if(project!=null)project(project,action);
        else if(!"query".equals(action) || !permissions.isAdministrator() && !Objects.equals(number(row.get("creator_id")),SecurityUtils.getUserId()))throw denied();
        return row;
    }
    public void prepare(ArchitectureDocument d,String action) {
        if(d.getId()!=null){var old=document(d.getId(),action);Long owner=number(old.get("project_id"));
            if(owner==null)throw new AccessDeniedException("历史架构尚未确认项目归属，保留为只读；请新建明确项目的架构");
            if(d.getProjectId()!=null && !owner.equals(d.getProjectId()))throw denied();d.setProjectId(owner);
        }
        project(d.getProjectId(),action);d.setCanEdit(false);
    }
    private Set<Long> ownedModels(Long project) {
        return new HashSet<>(jdbc.queryForList("SELECT m.id FROM dp_model m LEFT JOIN dp_warehouse_space s ON s.id=JSON_UNQUOTE(JSON_EXTRACT(m.warehouse_config,'$.spaceId')) WHERE m.del_flag='0' AND m.warehouse_config IS NOT NULL AND (s.project_id=? OR (s.id IS NULL AND EXISTS(SELECT 1 FROM da_asset a JOIN da_asset_project_rel r ON r.asset_id=a.id WHERE a.datasource_id=m.datasource_id AND a.database_name=m.database_name AND a.table_name=m.model_name AND a.del_flag='0' AND r.project_id=? AND r.del_flag='0' AND r.valid_flag='1'))) AND EXISTS(SELECT 1 FROM da_datasource_project_rel r WHERE r.datasource_id=m.datasource_id AND r.project_id=? AND r.valid_flag='1')",Long.class,project,project,project));
    }
    public List<WarehouseDefinition> models(Long project) {
        project(project,"query");Set<Long> ids=ownedModels(project);return warehouse.list().stream().filter(model->ids.contains(model.getId())).toList();
    }
    public void datasource(Long project,Long id) {
        if(id==null || !permissions.any(project,"da:dataSource:list","da:dataSource:query"))throw denied();data.requireDatasourceConfiguration(id);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM da_datasource_project_rel WHERE datasource_id=? AND project_id=? AND valid_flag='1'",Long.class,id,project)<1)throw denied();
    }
    public void api(Long project,Long id) {if(project==null || !project.equals(apis.requireReadableApiProject(id)))throw denied();}
    public void task(Long project,Long id) {if(project==null || !project.equals(tasks.requireReadableTaskProject(id)))throw denied();}
    public Map<String,Object> catalog(Long project) {
        project(project,"list");List<Map<String,Object>> sources=new ArrayList<>();
        if(permissions.any(project,"da:dataSource:list","da:dataSource:query")){
            var ids=data.configurableDatasourceIds();
            for(var row:jdbc.queryForList("SELECT DISTINCT d.id,d.datasource_name name,d.datasource_type engine,d.ip address,d.port FROM da_datasource d JOIN da_datasource_project_rel r ON r.datasource_id=d.id WHERE d.del_flag='0' AND r.project_id=? AND r.valid_flag='1' ORDER BY d.id",project))if(ids.contains(number(row.get("id"))))sources.add(row);
        }
        var modelList=permissions.has(project,"dp:model:query")?models(project):List.of();
        return Map.of("datasources",sources,"models",modelList,"apis",catalogRows("ds_api",project,apis.readableApiIds()),"tasks",catalogRows("dpp_etl_task",project,tasks.readableTaskIds()));
    }
    private List<Map<String,Object>> catalogRows(String table,Long project,List<Long> allowed) {
        if(allowed.isEmpty())return List.of();List<Object> args=new ArrayList<>();args.add(project);args.addAll(allowed);
        return jdbc.queryForList("SELECT id,name,status FROM "+table+" WHERE project_id=? AND del_flag='0' AND id IN ("+marks(allowed.size())+") ORDER BY id",args.toArray());
    }
    private static Long number(Object value){return value instanceof Number n?n.longValue():null;}
    private static String marks(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private static AccessDeniedException denied(){return new AccessDeniedException("架构或关联资源不属于当前项目的可操作范围");}
}
