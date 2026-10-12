package tech.qiantong.qdata.module.ds.service.api;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.database.core.HiveReadQuery;
import tech.qiantong.qdata.common.database.core.HiveTableIdentity;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.module.ds.dal.dataobject.api.*;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;

/** API ownership and target-project capabilities precede data access or forwarding. */
@Service
@RequiredArgsConstructor
public class ServiceResourceAccess implements tech.qiantong.qdata.module.ds.api.service.ServiceManagementAccess {
    @Override public List<Long> readableApiIds(){return apiIds("read");}
    @Override public Long requireReadableApiProject(Long id){return number(api(id,"read").get("project_id"));}
    private final JdbcTemplate jdbc;
    private final DataResourceAccess data;
    private final ProjectPermissionService permissions;
    private final IDaDatasourceApiService sources;
    public List<Long> projects(){
        if(permissions.isAdministrator())return jdbc.queryForList("SELECT id FROM att_project WHERE del_flag='0' AND valid_flag='1'",Long.class);
        Long user=SecurityUtils.getUserId();return jdbc.queryForList("SELECT p.id FROM att_project p WHERE p.del_flag='0' AND p.valid_flag='1' AND (p.manager_id=? OR EXISTS(SELECT 1 FROM att_project_user_rel r WHERE r.project_id=p.id AND r.user_id=? AND r.del_flag='0' AND r.valid_flag='1'))",Long.class,user,user);
    }
    private String[] capabilities(String mode){return switch(mode){case "read"->new String[]{"ds:api:list","ds:api:query"};case "author"->new String[]{"ds:api:add","ds:api:edit"};case "logs"->new String[]{"ds:apiLog:list","ds:apiLog:query","ds:apilog:query","ds:apiLog:export"};default->new String[]{"ds:api:"+mode};};}
    public Long project(Long id,String mode){
        var visible=projects();if(id==null){var eligible=visible.stream().filter(project->permissions.any(project,capabilities(mode))).toList();if(eligible.size()!=1)throw new AccessDeniedException("请选择有权限的服务所属项目");id=eligible.get(0);}
        if(!visible.contains(id) || !permissions.any(id,capabilities(mode)))throw denied();return id;
    }
    public List<Map<String,Object>> projectOptions(boolean editable){
        List<Map<String,Object>> result=new ArrayList<>();for(Long id:projects())if(permissions.any(id,editable?new String[]{"ds:api:add","ds:api:edit"}:capabilities("read")))result.add(jdbc.queryForMap("SELECT id,name FROM att_project WHERE id=?",id));return result;
    }
    public List<Long> apiIds(String mode){
        var projects=new HashSet<>(projects());var allowed=new ArrayList<Long>();Map<Long,Boolean> grants=new HashMap<>();boolean admin=permissions.isAdministrator();
        for(var row:jdbc.queryForList("SELECT id,project_id,creator_id FROM ds_api"+("logs".equals(mode)?"":" WHERE del_flag='0'"))){
            Long project=number(row.get("project_id"));boolean visible=admin || (project!=null?projects.contains(project) && grants.computeIfAbsent(project,key->permissions.any(key,capabilities(mode))):Objects.equals(number(row.get("creator_id")),SecurityUtils.getUserId()) && Arrays.stream(capabilities(mode)).anyMatch(SecurityUtils::hasPermi));
            if(visible)allowed.add(number(row.get("id")));
        }
        return allowed;
    }
    public Map<String,Object> api(Long id,String mode){
        if(id==null)throw denied();var rows=jdbc.queryForList("SELECT id,project_id,creator_id,status,config_json,api_service_type,api_id,transmit_type FROM ds_api WHERE id=? AND del_flag='0'",id);if(rows.size()!=1)throw denied();var row=rows.get(0);Long project=number(row.get("project_id"));
        if(project!=null)project(project,mode);else if(!permissions.isAdministrator() && (!Objects.equals(number(row.get("creator_id")),SecurityUtils.getUserId()) || Arrays.stream(capabilities(mode)).noneMatch(SecurityUtils::hasPermi)))throw denied();return row;
    }
    public void prepare(DsApiDO request,String mode,boolean existing){
        if(existing){var old=api(request.getId(),mode);Long project=number(old.get("project_id"));if(project!=null && request.getProjectId()!=null && !project.equals(request.getProjectId()))throw denied();request.setProjectId(project!=null?project:project(request.getProjectId(),mode));
            if(request.getStatus()==null)request.setStatus(Objects.toString(old.get("status"),"0"));
            if(request.getApiServiceType()==null)request.setApiServiceType(Objects.toString(old.get("api_service_type"),null));
            if(request.getExecuteConfig()==null && request.getConfigJson()==null)request.setConfigJson(Objects.toString(old.get("config_json"),null));
        }
        else {if(request.getId()!=null)throw denied();request.setProjectId(project(request.getProjectId(),mode));}
        if(request.getHeaderJson()!=null && request.getHeaderJson().contains("[REDACTED]"))throw new IllegalArgumentException("请刷新完整配置后修改敏感请求头");
        request.setCreatorId(null);request.setCreateBy(null);request.setCreateTime(null);
    }
    public void log(Long id){
        var rows=jdbc.queryForList("SELECT api_id FROM ds_api_log WHERE id=? AND del_flag='0'",Long.class,id);if(rows.size()!=1 || !apiIds("logs").contains(rows.get(0)))throw denied();
    }
    public void generatedLog(){throw new AccessDeniedException("调用日志由实际请求生成并保留，不支持独立增删改");}
    public List<Map<String,Object>> datasourceOptions(Long project){
        project=project(project,"author");var result=jdbc.queryForList("SELECT DISTINCT d.id,d.datasource_name datasourceName,d.datasource_type datasourceType,d.valid_flag validFlag FROM da_datasource d JOIN da_datasource_project_rel r ON r.datasource_id=d.id WHERE r.project_id=? AND r.valid_flag='1' AND d.del_flag='0'",project);result.forEach(row->row.put("validFlag",Boolean.TRUE.equals(row.get("validFlag")) || "1".equals(Objects.toString(row.get("validFlag"),""))));return result;
    }
    private void sourceProject(Long project,Long source,boolean execute){
        if(project==null || source==null)throw denied();if(execute)data.requireDatasource(source);else data.requireDatasourceConfiguration(source);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM da_datasource_project_rel r JOIN att_project p ON p.id=r.project_id WHERE r.datasource_id=? AND r.project_id=? AND r.valid_flag='1' AND p.del_flag='0' AND p.valid_flag='1'",Long.class,source,project)<1)throw new AccessDeniedException("连接未授权给服务所属项目");
    }
    public void query(Long project,Long source,String sql,boolean execute){
        sourceProject(project,source,execute);var datasource=sources.getDatasourceById(source);if(datasource==null)throw denied();var config=JSON.parseObject(datasource.getDatasourceConfig());String database=config.getString("dbname");
        String parsed=sql==null?null:sql.replace("${","").replace("}","");Set<String> tables;
        if("Hive".equalsIgnoreCase(datasource.getDatasourceType()))tables=HiveReadQuery.tables(parsed,database);
        else {
            String type=datasource.getDatasourceType().toLowerCase(Locale.ROOT);com.alibaba.druid.DbType dialect=type.contains("postgres") || type.contains("kingbase")?com.alibaba.druid.DbType.postgresql:type.contains("oracle") || type.startsWith("dm")?com.alibaba.druid.DbType.oracle:type.contains("sqlserver") || type.contains("sql_server")?com.alibaba.druid.DbType.sqlserver:com.alibaba.druid.DbType.mysql;
            tables=tech.qiantong.qdata.common.database.core.ReadOnlySql.tables(parsed,dialect);
        }
        for(String table:tables){
            if(execute)data.requireTable(source,table);else data.requireMetadataTable(source,table);
            if("Hive".equalsIgnoreCase(datasource.getDatasourceType())){
                var owners=data.owningHiveProjects(source,table);
                if(!owners.isEmpty() && (owners.size()!=1 || !owners.contains(project)))throw new AccessDeniedException("服务SQL不能引用其他项目的数仓数据库");
            }
        }
    }
    public void definition(DsApiDO request,boolean execute){
        if("3".equals(request.getApiServiceType())){forward(request.getProjectId(),request.getTransmitType(),request.getApiId());return;}
        var config=request.getExecuteConfig();if(config==null && request.getConfigJson()!=null){config=JSON.parseObject(request.getConfigJson(),ExecuteConfig.class);request.setExecuteConfig(config);}if(config==null)throw new IllegalArgumentException("服务缺少执行配置");
        if(!Set.of("1","2").contains(Objects.toString(config.getApiServiceType(),"")) || !Objects.equals(config.getApiServiceType(),request.getApiServiceType()))throw new IllegalArgumentException("服务类型与执行配置不一致");
        query(request.getProjectId(),parse(config.getSourceId()),config.getSqlText(),execute);
        request.setConfigJson(JSON.toJSONString(config));
    }
    public void forward(Long project,String type,String id){
        String table=switch(Objects.toString(type,"")){case "1"->"da_asset_api";case "2"->"da_asset_gis";default->throw new IllegalArgumentException("转发类型无效");};
        var assets=jdbc.queryForList("SELECT asset_id FROM "+table+" WHERE id=? AND del_flag='0' AND valid_flag='1'",Long.class,parse(id));if(assets.size()!=1)throw denied();Long asset=assets.get(0);data.requireAsset(asset);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM da_asset a LEFT JOIN dp_warehouse_dataset d ON d.id=a.dataset_id LEFT JOIN dp_warehouse_space s ON s.id=d.space_id WHERE a.id=? AND a.del_flag='0' AND (s.project_id=? OR EXISTS(SELECT 1 FROM da_asset_project_rel r WHERE r.asset_id=a.id AND r.project_id=? AND r.del_flag='0' AND r.valid_flag='1'))",Long.class,asset,project,project)<1)throw new AccessDeniedException("转发资产未授权给服务所属项目");
    }
    public void publication(DsApiDO value){
        api(value.getId(),"edit");
        if(managed(value.getId())){
            Long ready=jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_serving_product p JOIN dp_warehouse_serving_head h ON h.space_id=p.space_id JOIN dp_warehouse_serving_release r ON r.id=h.release_id JOIN dp_warehouse_serving_release_product rp ON rp.release_id=r.id AND rp.dataset_id=p.dataset_id AND rp.contract_version=p.contract_version WHERE p.api_id=? AND r.status='PUBLISHED' AND rp.status='VERIFIED'",Long.class,value.getId());
            if(ready==0)throw new IllegalArgumentException("请先通过数仓发布完成当前服务契约的就绪检查");
        }else definition(value,true);
    }
    public tech.qiantong.qdata.module.ds.controller.admin.api.vo.DsApiRespVO present(tech.qiantong.qdata.module.ds.controller.admin.api.vo.DsApiRespVO value){
        if(value==null)return null;
        if(value.getProjectId()!=null && value.getProjectName()==null){var names=jdbc.queryForList("SELECT name FROM att_project WHERE id=?",String.class,value.getProjectId());if(names.size()==1)value.setProjectName(names.get(0));}
        value.setCanEdit(value.getProjectId()==null?SecurityUtils.hasPermi("ds:api:edit"):permissions.any(value.getProjectId(),"ds:api:edit"));
        if(!value.getCanEdit()){value.setHeaderJson(tech.qiantong.qdata.common.utils.SecretRedactor.redact(value.getHeaderJson()));value.setConfigJson(tech.qiantong.qdata.common.utils.SecretRedactor.redact(value.getConfigJson()));}return value;
    }
    public tech.qiantong.qdata.module.ds.controller.admin.apiLog.vo.DsApiLogRespVO present(tech.qiantong.qdata.module.ds.controller.admin.apiLog.vo.DsApiLogRespVO value){
        if(value==null)return null;value.setCallerParams(tech.qiantong.qdata.common.utils.SecretRedactor.redact(value.getCallerParams()));value.setCallerUrl(tech.qiantong.qdata.common.utils.SecretRedactor.redact(value.getCallerUrl()));value.setMsg(tech.qiantong.qdata.common.utils.SecretRedactor.redact(value.getMsg()));return value;
    }
    public boolean managed(Long id){return id!=null && jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_serving_product WHERE api_id=?",Long.class,id)>0;}
    public static Long parse(String value){if(value==null || !value.matches("[0-9]+"))throw denied();try{return Long.valueOf(value);}catch(NumberFormatException invalid){throw denied();}}
    private static Long number(Object value){return value==null?null:((Number)value).longValue();}
    private static AccessDeniedException denied(){return new AccessDeniedException("服务、调用日志或关联资源不属于当前授权范围");}
}
