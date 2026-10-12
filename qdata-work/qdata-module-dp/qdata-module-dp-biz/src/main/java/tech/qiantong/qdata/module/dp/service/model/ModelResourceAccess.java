package tech.qiantong.qdata.module.dp.service.model;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;

/** Native model APIs resolve the stored model project before interpreting any child or client input. */
@Service
@RequiredArgsConstructor
public class ModelResourceAccess implements tech.qiantong.qdata.module.dp.api.service.model.ModelManagementAccess {
    private final JdbcTemplate jdbc;
    private final DataResourceAccess data;
    private final ProjectPermissionService permissions;
    private static final Set<String> READ=Set.of("list","query","export","columnQuery","columnExport","materializationRead");
    private static final String ROWS="SELECT m.id,m.project_id,m.creator_id,m.datasource_id,m.status,m.document_id,m.cat_code,m.warehouse_config,NULLIF(JSON_UNQUOTE(JSON_EXTRACT(m.warehouse_config,'$.spaceId')),'null') warehouse_space_id,s.project_id warehouse_project_id FROM dp_model m LEFT JOIN dp_warehouse_space s ON s.id=JSON_UNQUOTE(JSON_EXTRACT(m.warehouse_config,'$.spaceId'))";
    private String[] required(String action) {
        if(action.startsWith("column"))return new String[]{"dp:model:modelcolumn:"+action.substring(6).toLowerCase(Locale.ROOT)};
        return switch(action){case "status"->new String[]{"dp:model:edit"};case "materializationRead"->new String[]{"dp:releaseModel:list"};case "materialize"->new String[]{"dp:model:edit","dp:releaseModel:list"};default->new String[]{"dp:model:"+action};};
    }
    private boolean capability(Long project,String action){return Arrays.stream(required(action)).allMatch(p->permissions.has(project,p));}
    private Long owner(Map<String,Object> row) {
        Long explicit=number(row.get("project_id")),warehouse=number(row.get("warehouse_project_id"));
        if(row.get("warehouse_space_id")!=null && warehouse==null)throw denied();
        if(explicit!=null && warehouse!=null && !explicit.equals(warehouse))throw denied();
        return warehouse==null?explicit:warehouse;
    }
    public void project(Long id,String action){if(id==null || !data.visibleProjectIds().contains(id) || !capability(id,action))throw denied();}
    private class Context {
        final Set<Long> projects=data.visibleProjectIds(),sources=data.configurableDatasourceIds();
        final boolean administrator=permissions.isAdministrator();
        final Map<String,Boolean> grants=new HashMap<>(),assignments=new HashMap<>();
        final Map<Long,String> names=new HashMap<>();
        String name(Long id){return id==null?"未确认归属":names.computeIfAbsent(id,p->jdbc.queryForObject("SELECT name FROM att_project WHERE id=?",String.class,p));}
        boolean grant(Long project,String action){return grants.computeIfAbsent(project+":"+action,k->capability(project,action));}
        boolean assigned(Long source,Long project){return assignments.computeIfAbsent(source+":"+project,k->jdbc.queryForObject("SELECT COUNT(*) FROM da_datasource_project_rel WHERE datasource_id=? AND project_id=? AND valid_flag='1'",Long.class,source,project)>0);}
    }
    private boolean allowed(Map<String,Object> row,String action,Context context) {
        Long project=owner(row);
        if(project!=null){
            if(!context.projects.contains(project) || !context.grant(project,action))return false;
            Long source=number(row.get("datasource_id"));
            return source==null || context.sources.contains(source) && context.assigned(source,project);
        }
        if(!READ.contains(action))return false;
        if(context.administrator)return true;
        if(Objects.equals(number(row.get("creator_id")),SecurityUtils.getUserId()) && Arrays.stream(required(action)).allMatch(SecurityUtils::hasPermi))return true;
        var projects=jdbc.queryForList("SELECT DISTINCT r.project_id FROM dp_model m JOIN da_asset a ON a.datasource_id=m.datasource_id AND a.table_name=m.model_name AND a.database_name=m.database_name JOIN da_asset_project_rel r ON r.asset_id=a.id WHERE m.id=? AND a.del_flag='0' AND r.del_flag='0' AND r.valid_flag='1'",Long.class,row.get("id"));
        return projects.stream().anyMatch(p->context.projects.contains(p) && context.grant(p,action));
    }
    public List<Long> modelIds(String action,Long project,boolean history) {
        if(project!=null)project(project,action);
        List<Long> result=new ArrayList<>();Context context=new Context();
        for(var row:jdbc.queryForList(ROWS+(history?"":" WHERE m.del_flag='0'")))try{
            if((project==null || Objects.equals(owner(row),project)) && allowed(row,action,context))result.add(number(row.get("id")));
        }catch(AccessDeniedException invalid){/* Invalid ownership never becomes a global record. */}
        return result;
    }
    public Map<String,Object> model(Long id,String action){return model(id,action,false);}
    public Map<String,Object> model(Long id,String action,boolean history) {
        if(id==null)throw denied();var rows=jdbc.queryForList(ROWS+" WHERE m.id=?"+(history?"":" AND m.del_flag='0'"),id);
        if(rows.size()!=1 || !allowed(rows.get(0),action,new Context()))throw denied();return rows.get(0);
    }
    public Long modelProject(Long id,String action){return owner(model(id,action));}
    public void editable(Long id,String action){var row=model(id,action);if(row.get("warehouse_config")!=null)throw new AccessDeniedException("此模型由Hive分层流程维护，请使用对应的数仓模型入口");if(!Set.of("status","materialize").contains(action) && "1".equals(Objects.toString(row.get("status"),"")))throw new IllegalArgumentException("已发布模型不可直接修改，请先取消逻辑发布");}
    @Override public void requireModelColumns(Long id){model(id,"columnQuery");}
    public Long column(Long id,String action) {
        if(id==null)throw denied();var parents=jdbc.queryForList("SELECT model_id FROM dp_model_column WHERE id=? AND del_flag='0'",Long.class,id);if(parents.size()!=1)throw denied();
        if(READ.contains(action))model(parents.get(0),action);else editable(parents.get(0),action);return parents.get(0);
    }
    public Long materialization(Long id,String action) {
        if(id==null)throw denied();var parents=jdbc.queryForList("SELECT model_id FROM dp_model_materialized WHERE id=? AND del_flag='0'",Long.class,id);if(parents.size()!=1)throw denied();
        model(parents.get(0),action,true);return parents.get(0);
    }
    public void logicalStatus(Long id,Long status) {
        editable(id,"status");if(status==null || status<0 || status>1)throw new IllegalArgumentException("模型状态无效");
        if(status==1 && jdbc.queryForObject("SELECT COUNT(*) FROM dp_model_column WHERE model_id=? AND del_flag='0'",Long.class,id)==0)throw new IllegalArgumentException("模型至少需要一个字段才能发布");
    }
    public void targetDatasource(Long model,Long source) {
        Long project=modelProject(model,"materialize");editable(model,"materialize");if(!"1".equals(Objects.toString(this.model(model,"materialize").get("status"),"")))throw new IllegalArgumentException("模型尚未完成逻辑发布");data.requireDatasource(source);
        if(project==null || jdbc.queryForObject("SELECT COUNT(*) FROM da_datasource_project_rel WHERE datasource_id=? AND project_id=? AND valid_flag='1'",Long.class,source,project)<1)throw denied();
    }
    public List<Map<String,Object>> projectOptions() {
        List<Map<String,Object>> result=new ArrayList<>();
        for(Long id:data.visibleProjectIds())if(capability(id,"list") || capability(id,"add")){
            var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT id,name FROM att_project WHERE id=?",id));row.put("canAdd",capability(id,"add"));row.put("canCreateWithFields",capability(id,"add") && capability(id,"columnAdd"));row.put("canImport",capability(id,"import"));row.put("canExport",capability(id,"export"));result.add(row);
        }
        return result;
    }
    public List<Map<String,Object>> materializationSources(List<Long> ids) {
        if(ids==null || ids.isEmpty())throw new IllegalArgumentException("请选择模型");Set<Long> projects=new HashSet<>();
        for(Long id:ids){var row=model(id,"materialize");editable(id,"materialize");if(!"1".equals(Objects.toString(row.get("status"),"")))throw new IllegalArgumentException("模型尚未完成逻辑发布");projects.add(owner(row));}
        var allowed=data.configurableDatasourceIds();List<Map<String,Object>> result=new ArrayList<>();
        for(var row:jdbc.queryForList("SELECT id,datasource_name datasourceName,datasource_type datasourceType,ip,port,valid_flag validFlag FROM da_datasource WHERE del_flag='0' ORDER BY id")){
            if("Hive".equalsIgnoreCase(Objects.toString(row.get("datasourceType"),"")))continue;
            Long id=number(row.get("id"));if(!allowed.contains(id))continue;
            if(projects.stream().allMatch(project->jdbc.queryForObject("SELECT COUNT(*) FROM da_datasource_project_rel WHERE datasource_id=? AND project_id=? AND valid_flag='1'",Long.class,id,project)>0)){row.put("validFlag",Boolean.TRUE.equals(row.get("validFlag")) || "1".equals(Objects.toString(row.get("validFlag"),"")));result.add(row);}
        }
        return result;
    }
    public List<Map<String,Object>> datasourceOptions(Long project) {
        if(project==null || !data.visibleProjectIds().contains(project) || !permissions.any(project,"dp:model:add","dp:model:edit"))throw denied();
        if(!permissions.has(project,"da:dataSource:query"))return List.of();
        Set<Long> allowed=data.configurableDatasourceIds();
        var rows=jdbc.queryForList("SELECT DISTINCT d.id,d.datasource_name datasourceName,d.datasource_type datasourceType,d.ip,d.port,d.valid_flag validFlag FROM da_datasource d JOIN da_datasource_project_rel r ON r.datasource_id=d.id WHERE d.del_flag='0' AND r.project_id=? AND r.valid_flag='1' ORDER BY d.id",project);
        rows.removeIf(row->!allowed.contains(number(row.get("id"))));rows.forEach(row->row.put("validFlag",Boolean.TRUE.equals(row.get("validFlag")) || "1".equals(Objects.toString(row.get("validFlag"),""))));return rows;
    }
    public Map<String,Boolean> capabilities(Long id) {
        var row=model(id,"query");return capabilities(row,new Context());
    }
    private Map<String,Boolean> capabilities(Map<String,Object> row,Context context) {
        Map<String,Boolean> result=new LinkedHashMap<>();
        for(String action:List.of("query","edit","status","remove","export","columnQuery","columnAdd","columnEdit","columnRemove","columnExport","columnImport","materializationRead","materialize"))result.put(action,(READ.contains(action) || row.get("warehouse_config")==null && (Set.of("status","materialize").contains(action) || !"1".equals(Objects.toString(row.get("status"),"")))) && (!"materialize".equals(action) || "1".equals(Objects.toString(row.get("status"),""))) && allowed(row,action,context));
        return result;
    }
    public record Presentation(Long projectId,String projectName,boolean managed,Map<String,Boolean> capabilities) {}
    public Map<Long,Presentation> presentations(Collection<Long> ids) {
        if(ids.isEmpty())return Map.of();Context context=new Context();Map<Long,Presentation> result=new HashMap<>();
        for(var row:jdbc.queryForList(ROWS+" WHERE m.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") AND m.del_flag='0'",ids.toArray()))result.put(number(row.get("id")),new Presentation(owner(row),context.name(owner(row)),row.get("warehouse_config")!=null,capabilities(row,context)));
        return result;
    }
    public void prepare(tech.qiantong.qdata.module.dp.controller.admin.model.vo.DpModelSaveReqVO request,String action) {
        if(request.getId()!=null){
            var old=model(request.getId(),action);editable(request.getId(),action);
            Long project=owner(old);if(request.getProjectId()!=null && !request.getProjectId().equals(project))throw denied();request.setProjectId(project);
            request.setStatus(Objects.toString(old.get("status"),"0"));
            request.setCreatorId(null);request.setCreateBy(null);request.setCreateTime(null);
        }else {project(request.getProjectId(),action);request.setStatus("0");}
        identifier(request.getModelName());
        if(request.getModelComment()==null || request.getModelComment().isBlank() || !Set.of("1","2","3","4").contains(Objects.toString(request.getTableType(),"")))throw new IllegalArgumentException("请填写模型名称和有效表类型");
        definitionSource(request.getProjectId(),parse(request.getDatasourceId()));referenceMetadata(request.getDocumentId(),request.getCatCode());
    }
    public void columnRequest(tech.qiantong.qdata.module.dp.controller.admin.model.vo.DpModelColumnSaveReqVO request,String action) {
        if(request.getId()!=null){Long parent=column(request.getId(),action);if(request.getModelId()!=null && !parent.equals(request.getModelId()))throw denied();request.setModelId(parent);request.setCreatorId(null);request.setCreateBy(null);request.setCreateTime(null);}
        else editable(request.getModelId(),action);
        identifier(request.getEngName());
    }
    public void columnBatch(List<tech.qiantong.qdata.module.dp.controller.admin.model.vo.DpModelColumnSaveReqVO> rows,boolean replace) {
        if(rows==null || rows.isEmpty())throw new IllegalArgumentException("字段列表不能为空");
        Set<Long> parents=new HashSet<>(),ids=new HashSet<>();Set<String> names=new HashSet<>();
        for(var row:rows){
            if(row==null)throw new IllegalArgumentException("字段定义不能为空");
            if(!replace && row.getId()!=null)throw new IllegalArgumentException("新增字段不能携带已有字段标识");
            columnRequest(row,row.getId()==null?"columnAdd":"columnEdit");parents.add(row.getModelId());
            if(row.getId()!=null && !ids.add(row.getId()) || !names.add(row.getEngName().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("字段标识或名称重复");
        }
        if(parents.size()!=1)throw new IllegalArgumentException("一次字段批量操作只能包含同一个模型");
        if(replace){Long model=parents.iterator().next();editable(model,"columnEdit");
            var old=jdbc.queryForList("SELECT id FROM dp_model_column WHERE model_id=? AND del_flag='0'",Long.class,model);
            if(old.stream().anyMatch(id->!ids.contains(id)))editable(model,"columnRemove");
        }
    }
    public static Long parse(String value){if(value==null || value.isBlank())return null;try{return Long.valueOf(value);}catch(NumberFormatException invalid){throw new IllegalArgumentException("资源标识无效");}}
    private static void identifier(String name){if(name==null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,255}"))throw new IllegalArgumentException("模型或字段编码只能使用字母、数字和下划线，且不能以数字开头");}
    public void referenceMetadata(Long document,String category) {
        if(document!=null && jdbc.queryForObject("SELECT COUNT(*) FROM dp_document WHERE id=? AND del_flag='0' AND valid_flag='1'",Long.class,document)<1)throw new IllegalArgumentException("关联标准不存在或已停用");
        if(category!=null && !category.isBlank() && jdbc.queryForObject("SELECT COUNT(*) FROM att_model_cat WHERE code=? AND del_flag='0' AND valid_flag='1'",Long.class,category)!=1)throw new IllegalArgumentException("模型分类不存在或已停用");
    }
    public void definitionSource(Long project,Long source) {
        if(source==null)return;data.requireDatasourceConfiguration(source);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM da_datasource_project_rel WHERE datasource_id=? AND project_id=? AND valid_flag='1'",Long.class,source,project)<1)throw denied();
    }
    private static Long number(Object value){return value instanceof Number n?n.longValue():null;}
    private static AccessDeniedException denied(){return new AccessDeniedException("模型或其字段、物化记录不属于当前项目的可操作范围");}
}
