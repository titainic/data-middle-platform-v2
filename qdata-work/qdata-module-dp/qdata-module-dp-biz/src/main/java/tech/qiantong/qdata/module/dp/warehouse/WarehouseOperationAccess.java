package tech.qiantong.qdata.module.dp.warehouse;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;

/** Public management operations require every capability on the resource's actual project. */
@Service
@RequiredArgsConstructor
public class WarehouseOperationAccess {
    private final JdbcTemplate jdbc;
    private final WarehouseSpaceService spaces;
    private final WarehouseService models;
    private final ProjectPermissionService permissions;
    private static final String[] MANAGE={"dp:model:edit","da:dataSource:edit","att:project:edit"};

    public void project(Long id,String... capabilities){
        if(id==null || spaces.projects().stream().noneMatch(p->((Number)p.get("id")).longValue()==id))throw denied();
        if(!all(id,capabilities))throw denied();
    }
    private boolean all(Long id,String... capabilities){return Arrays.stream(capabilities).allMatch(capability->permissions.has(id,capability));}
    public void space(Long id,String... capabilities){
        if(id==null)throw denied();var space=spaces.getInternal(id);project(space.getProjectId(),capabilities);
        if(!permissions.isAdministrator() && jdbc.queryForObject("SELECT COUNT(*) FROM da_datasource_project_rel WHERE datasource_id=? AND project_id=? AND valid_flag='1'",Long.class,space.getDatasourceId(),space.getProjectId())<1)throw denied();
    }
    public void model(Long id,String... capabilities){
        if(id==null)throw denied();var model=models.getInternal(id);
        if(model.getSpaceId()!=null)space(model.getSpaceId(),capabilities);
        else {spaces.authorizeLegacy(id);if(!all(null,capabilities))throw denied();}
    }
    public void definition(WarehouseDefinition value,String... capabilities){
        if(value.getId()!=null)model(value.getId(),capabilities);
        if(value.getSpaceId()!=null)space(value.getSpaceId(),capabilities);
        if(value.getId()==null && value.getSpaceId()==null)throw denied();
        if(value.getUpstreamIds()!=null)for(Long id:value.getUpstreamIds())model(id,"dp:model:query");
        if(value.getDimensions()!=null)for(var dimension:value.getDimensions()){
            if(dimension==null)throw denied();model(dimension.getModelId(),"dp:model:query");
        }
    }
    private Long owner(String query,Object id){if(id==null)throw denied();var result=jdbc.queryForList(query,Long.class,id);if(result.size()!=1)throw denied();return result.get(0);}
    public void batch(String id,String... capabilities){space(owner("SELECT space_id FROM dp_warehouse_batch WHERE id=?",id),capabilities);}
    public void assessment(String id,String... capabilities){space(owner("SELECT b.space_id FROM dp_warehouse_quality_assessment a JOIN dp_warehouse_batch b ON b.id=a.batch_id WHERE a.id=?",id),capabilities);}
    public void release(String id,String... capabilities){space(owner("SELECT space_id FROM dp_warehouse_serving_release WHERE id=?",id),capabilities);}
    public List<Map<String,Object>> projects(String capability){return spaces.projects().stream().filter(p->all(((Number)p.get("id")).longValue(),capability)).map(p->{var row=new LinkedHashMap<String,Object>(p);row.put("canCreateSpace",all(((Number)p.get("id")).longValue(),"dp:model:add","dp:model:edit","da:dataSource:edit","att:project:edit"));return (Map<String,Object>)row;}).toList();}
    public boolean canManage(){return spaces.projects().stream().anyMatch(p->all(((Number)p.get("id")).longValue(),MANAGE));}
    public void manageSpace(WarehouseSpaceService.Space value){project(value.getProjectId(),"dp:model:add","dp:model:edit","da:dataSource:edit","att:project:edit");}
    public void engine(WarehouseSpaceService.Engine value){
        if(value.getDatasourceId()==null)throw denied();
        boolean allowed=spaces.projects().stream().anyMatch(p->{Long id=((Number)p.get("id")).longValue();return all(id,MANAGE) && (permissions.isAdministrator() || jdbc.queryForObject("SELECT COUNT(*) FROM da_datasource_project_rel WHERE project_id=? AND datasource_id=? AND valid_flag='1'",Long.class,id,value.getDatasourceId())>0);});
        if(!allowed)throw denied();
    }
    public List<WarehouseSpaceService.Space> visibleSpaces(String capability){return spaces.spaces().stream().filter(s->all(s.getProjectId(),capability)).peek(s->s.setCapabilities(capabilities(s.getId()))).toList();}
    public List<WarehouseDefinition> visibleModels(String capability){
        Map<Long,Boolean> allowed=new HashMap<>();return models.list().stream().filter(model->{
            if(model.getSpaceId()==null)return all(null,capability);
            return allowed.computeIfAbsent(model.getSpaceId(),id->{try{space(id,capability);return true;}catch(AccessDeniedException|IllegalArgumentException denied){return false;}});
        }).toList();
    }
    public List<WarehouseSpaceService.Engine> visibleEngines(String capability){
        var projects=projects(capability);return spaces.engines().stream().filter(engine->projects.stream().anyMatch(p->permissions.isAdministrator() || jdbc.queryForObject("SELECT COUNT(*) FROM da_datasource_project_rel WHERE datasource_id=? AND project_id=? AND valid_flag='1'",Long.class,engine.getDatasourceId(),p.get("id"))>0)).toList();
    }
    public List<Map<String,Object>> datasources(){
        var projects=projects("da:dataSource:list");return models.datasourceList().stream().filter(source->projects.stream().anyMatch(p->permissions.isAdministrator() || jdbc.queryForObject("SELECT COUNT(*) FROM da_datasource_project_rel WHERE datasource_id=? AND project_id=? AND valid_flag='1'",Long.class,source.get("id"),p.get("id"))>0)).toList();
    }
    public Map<String,Boolean> capabilities(Long spaceId){
        space(spaceId);Long project=spaces.getInternal(spaceId).getProjectId();return capabilitiesForProject(project);
    }
    public Map<String,Boolean> legacyCapabilities(){return capabilitiesForProject(null);}
    private Map<String,Boolean> capabilitiesForProject(Long project){
        var result=new LinkedHashMap<String,Boolean>();
        result.put("modelRead",all(project,"dp:model:query"));result.put("modelWrite",all(project,"dp:model:add","dp:model:edit"));result.put("modelExecute",all(project,"dp:model:edit"));
        result.put("batchCreate",all(project,"dp:model:add","dp:model:edit"));result.put("batchRead",all(project,"dp:model:list"));result.put("batchQuery",all(project,"dp:model:query"));
        result.put("catalogRead",all(project,"dp:model:query","da:asset:list"));result.put("catalogWrite",all(project,"dp:model:edit","da:asset:add"));
        result.put("qualityRead",all(project,"da:qualityTask:info"));result.put("qualityWrite",all(project,"da:qualityTask:add","dp:model:edit"));result.put("qualityRun",all(project,"da:qualityTask:once"));
        result.put("servingRead",all(project,"dp:model:query"));result.put("servingConfigure",all(project,"dp:model:edit","da:dataSource:edit"));result.put("servingSync",all(project,"dp:model:edit","ds:api:add"));result.put("servingPublish",all(project,"dp:model:edit","ds:api:edit"));result.put("scopeWrite",all(project,"ds:api:edit"));return result;
    }
    private static AccessDeniedException denied(){return new AccessDeniedException("当前账号没有目标项目中该数仓操作的完整权限");}
}
