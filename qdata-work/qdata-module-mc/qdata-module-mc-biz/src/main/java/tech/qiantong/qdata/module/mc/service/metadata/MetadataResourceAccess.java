package tech.qiantong.qdata.module.mc.service.metadata;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import java.util.*;

/** Metadata follows the same dataset/connection namespace grants as its asset. */
@Service
@RequiredArgsConstructor
public class MetadataResourceAccess {
    private final JdbcTemplate jdbc;
    private final DataResourceAccess resources;
    private final MetadataProjectAccess projects;
    private boolean write(String action){return !Set.of("read","list","query","detail","export").contains(action);}
    public List<Long> tableIds(){return tableIds("read");}
    public List<Long> tableIds(String action){
        var request=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();String key=getClass().getName()+".tables."+SecurityUtils.getUserId()+"."+action;
        if(request!=null && request.getAttribute(key,0) instanceof List<?> cached)return cached.stream().map(value->((Number)value).longValue()).toList();
        var result=loadTableIds(action);if(request!=null)request.setAttribute(key,result,0);return result;
    }
    private List<Long> loadTableIds(String action){
        if(resources.isAdministrator())return jdbc.queryForList("SELECT id FROM mc_table WHERE del_flag='0'",Long.class);
        var sources=resources.configurableDatasourceIds();var assets=resources.visibleAssetIds();Set<Long> linked=new HashSet<>();
        if(!assets.isEmpty())linked.addAll(jdbc.queryForList("SELECT table_id FROM da_asset WHERE del_flag='0' AND table_id IS NOT NULL AND id IN ("+marks(assets.size())+")",Long.class,assets.toArray()));
        List<Long> allowed=new ArrayList<>();Map<String,Boolean> namespaces=new HashMap<>();
        for(var row:jdbc.queryForList("SELECT t.id,t.creator_id,t.datasource_id declared_source_id,d.datasource_id database_source_id,COALESCE(t.datasource_id,d.datasource_id) source_id,t.db_name,t.table_name FROM mc_table t LEFT JOIN mc_db d ON d.id=t.db_id WHERE t.del_flag='0'")) {
            Long id=number(row.get("id")),source=number(row.get("source_id"));
            if(row.get("declared_source_id")!=null && row.get("database_source_id")!=null && !Objects.equals(number(row.get("declared_source_id")),number(row.get("database_source_id"))))continue;
            String database=Objects.toString(row.get("db_name"),"");String table=Objects.toString(row.get("table_name"),"");
            String qualified=database.isBlank()?table:database+"."+table;Long creator=number(row.get("creator_id"));
            boolean visible=linked.contains(id) || source==null && Objects.equals(creator,SecurityUtils.getUserId());
            if(!visible && source!=null && sources.contains(source))visible=namespaces.computeIfAbsent(source+":"+database,key->{try{resources.requireMetadataTable(source,qualified);return true;}catch(AccessDeniedException denied){return false;}});
            if(visible && projects.table(id,source,qualified,creator,action,write(action)))allowed.add(id);
        }
        return allowed;
    }
    public void table(Long id){table(id,"read");}
    public void table(Long id,String action){if(id==null || !tableIds(action).contains(id))throw denied();}
    public void column(Long id){column(id,"read");}
    public void column(Long id,String action){var rows=jdbc.queryForList("SELECT table_id FROM mc_column WHERE id=? AND del_flag='0'",Long.class,id);if(rows.size()!=1)throw denied();table(rows.get(0),action);}
    public void source(Long id){resources.requireDatasourceConfiguration(id);}
    public List<Long> databaseIds(){return databaseIds("read");}
    public List<Long> databaseIds(String action){
        if(resources.isAdministrator())return jdbc.queryForList("SELECT id FROM mc_db WHERE del_flag='0'",Long.class);
        var allowed=new ArrayList<Long>();
        for(var row:jdbc.queryForList("SELECT id,datasource_id,db_name,creator_id FROM mc_db WHERE del_flag='0'")){
            Long id=number(row.get("id")),source=number(row.get("datasource_id"));
            if(source==null){if(projects.allowed(Set.of(),number(row.get("creator_id")),action,write(action)))allowed.add(id);continue;}
            String target=Objects.toString(row.get("db_name"),"")+".__metadata_scope";
            try{resources.requireMetadataTable(source,target);if(projects.source(source,target,action,write(action)))allowed.add(id);}catch(AccessDeniedException denied){/* Do not expose another namespace through its database header. */}
        }
        return allowed;
    }
    public List<tech.qiantong.qdata.module.mc.controller.admin.task.vo.McTaskSourceTreeRespVO> sourceTree(List<tech.qiantong.qdata.module.mc.controller.admin.task.vo.McTaskSourceTreeRespVO> rows){return sourceTree(rows,null,resources.configurableDatasourceIds());}
    private List<tech.qiantong.qdata.module.mc.controller.admin.task.vo.McTaskSourceTreeRespVO> sourceTree(List<tech.qiantong.qdata.module.mc.controller.admin.task.vo.McTaskSourceTreeRespVO> rows,Long source,Set<Long> allowed){
        List<tech.qiantong.qdata.module.mc.controller.admin.task.vo.McTaskSourceTreeRespVO> result=new ArrayList<>();if(rows==null)return result;
        for(var row:rows){
            Long next=source;
            if("DATASOURCE".equals(row.getType())){next=row.getId();if(!allowed.contains(next) || !projects.connectionBrowse(next))continue;}
            else if("DATABASE".equals(row.getType())){if(next==null)continue;try{resources.requireMetadataTable(next,row.getName()+".__metadata_scope");if(!projects.source(next,row.getName()+".__metadata_scope","browse",false))continue;}catch(AccessDeniedException denied){continue;}}
            else if(!"SOURCE".equals(row.getType()))continue;
            row.setChildren(sourceTree(row.getChildren(),next,allowed));if("SOURCE".equals(row.getType()) && row.getChildren().isEmpty())continue;result.add(row);
        }
        return result;
    }
    public tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McDbRespVO present(tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McDbRespVO value){return present(value,"read");}
    public tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McDbRespVO present(tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McDbRespVO value,String action){
        if(value==null)return null;var result=tech.qiantong.qdata.common.utils.object.BeanUtils.toBean(value,tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McDbRespVO.class);
        // Metadata is a catalog view. Connection credentials are maintained through its own authorized endpoint.
        result.setDatasourceConfig(tech.qiantong.qdata.common.database.core.ConnectionDisplayConfig.sanitize(value.getDatasourceConfig()));
        if(value.getDatasource()!=null){var source=tech.qiantong.qdata.common.utils.object.BeanUtils.toBean(value.getDatasource(),tech.qiantong.qdata.module.da.api.datasource.dto.DaDatasourceRespDTO.class);source.setDatasourceConfig(tech.qiantong.qdata.common.database.core.ConnectionDisplayConfig.sanitize(source.getDatasourceConfig()));result.setDatasource(source);}
        if(!resources.isAdministrator()){
            var tables=tableIds(action);String scope=tables.isEmpty()?" AND 1=0":" AND id IN ("+marks(tables.size())+")";
            List<Object> args=new ArrayList<>();args.add(value.getId());args.addAll(tables);
            result.setTableCount(Math.toIntExact(jdbc.queryForObject("SELECT COUNT(*) FROM mc_table WHERE del_flag='0' AND db_id=?"+scope,Long.class,args.toArray())));
            result.setDataRowCount(jdbc.queryForObject("SELECT COALESCE(SUM(row_count),0) FROM mc_table WHERE del_flag='0' AND db_id=?"+scope,Long.class,args.toArray()));
            String columns=tables.isEmpty()?" AND 1=0":" AND table_id IN ("+marks(tables.size())+")";
            result.setColumnCount(Math.toIntExact(jdbc.queryForObject("SELECT COUNT(*) FROM mc_column WHERE del_flag='0' AND db_id=?"+columns,Long.class,args.toArray())));
        }
        result.setCapabilities(capabilities(value.getId(),true));return result;
    }
    public tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McTableRespVO present(tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McTableRespVO value){return present(value,"read");}
    public tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McTableRespVO present(tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McTableRespVO value,String action){if(value==null)return null;var result=tech.qiantong.qdata.common.utils.object.BeanUtils.toBean(value,tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McTableRespVO.class);result.setDbRespVO(present(value.getDbRespVO(),action));result.setCapabilities(capabilities(value.getId(),false));return result;}
    public tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McColumnRespVO present(tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McColumnRespVO value){return present(value,"read");}
    public tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McColumnRespVO present(tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McColumnRespVO value,String action){if(value==null)return null;var result=tech.qiantong.qdata.common.utils.object.BeanUtils.toBean(value,tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McColumnRespVO.class);result.setTableRespVO(present(value.getTableRespVO(),action));result.setCapabilities(capabilities(value.getTableId(),false));return result;}
    private Map<String,Boolean> capabilities(Long id,boolean database){Map<String,Boolean> result=new LinkedHashMap<>();for(String action:List.of("query","add","edit","remove"))result.put(action,id!=null && (resources.isAdministrator() || (database?databaseIds(action):tableIds(action)).contains(id)));return result;}
    public void tableRequest(Long id,Long source,Long databaseId,String database,String table){
        String action=id==null?"add":"edit";
        if(id!=null){table(id,action);var old=jdbc.queryForMap("SELECT datasource_id,db_id,db_name,table_name FROM mc_table WHERE id=?",id);stable(source,old.get("datasource_id"));stable(databaseId,old.get("db_id"));stable(database,old.get("db_name"));stable(table,old.get("table_name"));if(source==null)source=number(old.get("datasource_id"));if(databaseId==null)databaseId=number(old.get("db_id"));if(database==null)database=Objects.toString(old.get("db_name"),"");if(table==null)table=Objects.toString(old.get("table_name"),"");}
        if(databaseId==null && id==null)throw new IllegalArgumentException("新增元数据表必须选择所属库");
        if(databaseId!=null){database(databaseId,action);var parent=jdbc.queryForMap("SELECT datasource_id,db_name FROM mc_db WHERE id=?",databaseId);Long databaseSource=number(parent.get("datasource_id"));if(source!=null && !source.equals(databaseSource))throw denied();stable(database,parent.get("db_name"));source=databaseSource;database=Objects.toString(parent.get("db_name"),"");}
        if(source!=null){String qualified=database==null||database.isBlank()?table:database+"."+table;resources.requireMetadataTable(source,qualified);if(!projects.source(source,qualified,action,true))throw denied();}
    }
    public void databaseRequest(Long id,Long source,String database){
        String action=id==null?"add":"edit";
        if(id!=null){database(id,action);var old=jdbc.queryForMap("SELECT datasource_id,db_name FROM mc_db WHERE id=?",id);stable(source,old.get("datasource_id"));stable(database,old.get("db_name"));if(source==null)source=number(old.get("datasource_id"));if(database==null)database=Objects.toString(old.get("db_name"),"");}
        if(source==null && id==null)throw new IllegalArgumentException("新增库元数据必须选择已登记的数据连接");
        if(source!=null){if(database==null || database.isBlank())throw denied();String qualified=database+".__metadata_scope";resources.requireMetadataTable(source,qualified);if(!projects.source(source,qualified,action,true))throw denied();}
    }
    public void columnRequest(tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McColumnSaveReqVO request){
        String action=request.getId()==null?"add":"edit";
        if(request.getId()!=null){column(request.getId(),action);var old=jdbc.queryForMap("SELECT * FROM mc_column WHERE id=?",request.getId());version(request.getVersion(),old.get("version"));request.setVersion(((Number)old.get("version")).intValue());stable(request.getColumnName(),old.get("column_name"));request.setColumnName(Objects.toString(old.get("column_name"),null));stable(request.getTableId(),old.get("table_id"));stable(request.getDbId(),old.get("db_id"));stable(request.getDatasourceId(),old.get("datasource_id"));request.setTableId(number(old.get("table_id")));request.setStatus(Objects.toString(old.get("status"),"0"));request.setPortalVisible(Objects.toString(old.get("portal_visible"),"0"));request.setTaskId(number(old.get("task_id")));audit(request,old);}
        else{newAudit(request);request.setTaskId(null);request.setVersion(1);request.setStatus("0");request.setPortalVisible("0");}
        table(request.getTableId(),action);var parent=jdbc.queryForMap("SELECT t.db_id,COALESCE(t.datasource_id,d.datasource_id) datasource_id FROM mc_table t LEFT JOIN mc_db d ON d.id=t.db_id WHERE t.id=?",request.getTableId());stable(request.getDbId(),parent.get("db_id"));stable(request.getDatasourceId(),parent.get("datasource_id"));request.setDbId(number(parent.get("db_id")));request.setDatasourceId(number(parent.get("datasource_id")));
        if(request.getMcTableLogId()!=null && jdbc.queryForObject("SELECT COUNT(*) FROM mc_table_log WHERE id=? AND table_id=?",Long.class,request.getMcTableLogId(),request.getTableId())!=1)throw denied();
    }
    public void databaseRequest(tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McDbSaveReqVO request){
        databaseRequest(request.getId(),request.getDatasourceId(),request.getDbName());
        if(request.getId()!=null){var old=jdbc.queryForMap("SELECT * FROM mc_db WHERE id=?",request.getId());version(request.getVersion(),old.get("version"));request.setVersion(((Number)old.get("version")).intValue());request.setDatasourceId(number(old.get("datasource_id")));request.setDbName(Objects.toString(old.get("db_name"),null));stable(request.getSchemaName(),old.get("schema_name"));request.setSchemaName(Objects.toString(old.get("schema_name"),null));request.setDatasourceConfig(Objects.toString(old.get("datasource_config"),null));request.setIp(Objects.toString(old.get("ip"),null));request.setPort(((Number)old.get("port")).intValue());request.setDbType(Objects.toString(old.get("db_type"),null));request.setTaskId(number(old.get("task_id")));request.setStatus(Objects.toString(old.get("status"),"0"));request.setPortalVisible(Objects.toString(old.get("portal_visible"),"0"));audit(request,old);}
        else{newAudit(request);var source=jdbc.queryForMap("SELECT datasource_config,ip,port,datasource_type FROM da_datasource WHERE id=? AND del_flag='0'",request.getDatasourceId());request.setDatasourceConfig(Objects.toString(source.get("datasource_config"),null));request.setIp(Objects.toString(source.get("ip"),null));request.setPort(((Number)source.get("port")).intValue());request.setDbType(Objects.toString(source.get("datasource_type"),null));request.setTaskId(null);request.setVersion(1);request.setStatus("0");request.setPortalVisible("0");}
    }
    public void tableRequest(tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McTableSaveReqVO request){
        tableRequest(request.getId(),request.getDatasourceId(),request.getDbId(),request.getDbName(),request.getTableName());
        if(request.getId()!=null){var old=jdbc.queryForMap("SELECT * FROM mc_table WHERE id=?",request.getId());version(request.getVersion(),old.get("version"));request.setVersion(((Number)old.get("version")).intValue());request.setTaskId(number(old.get("task_id")));request.setDatasourceId(number(old.get("datasource_id")));request.setDbId(number(old.get("db_id")));request.setDbName(Objects.toString(old.get("db_name"),null));request.setTableName(Objects.toString(old.get("table_name"),null));stable(request.getSchemaName(),old.get("schema_name"));request.setSchemaName(Objects.toString(old.get("schema_name"),null));request.setStatus(Objects.toString(old.get("status"),"0"));request.setPortalVisible(Objects.toString(old.get("portal_visible"),"0"));audit(request,old);}
        else{newAudit(request);var parent=jdbc.queryForMap("SELECT datasource_id,db_name FROM mc_db WHERE id=?",request.getDbId());request.setDatasourceId(number(parent.get("datasource_id")));request.setDbName(Objects.toString(parent.get("db_name"),null));request.setTaskId(null);request.setVersion(1);request.setStatus("0");request.setPortalVisible("0");}
    }
    private void updateAudit(tech.qiantong.qdata.common.core.domain.BaseEntity request){request.setUpdatorId(SecurityUtils.getUserId());request.setUpdateBy(SecurityUtils.getUsername());request.setUpdateTime(new java.util.Date());}
    private void newAudit(tech.qiantong.qdata.common.core.domain.BaseEntity request){request.setCreatorId(SecurityUtils.getUserId());request.setCreateBy(SecurityUtils.getUsername());request.setCreateTime(new java.util.Date());updateAudit(request);}
    private void audit(tech.qiantong.qdata.common.core.domain.BaseEntity request,Map<String,Object> old){request.setCreatorId(number(old.get("creator_id")));request.setCreateBy(Objects.toString(old.get("create_by"),null));Object date=old.get("create_time");if(date instanceof java.util.Date value)request.setCreateTime(value);else if(date instanceof java.time.LocalDateTime value)request.setCreateTime(java.sql.Timestamp.valueOf(value));updateAudit(request);}

    private void version(Integer requested,Object previous){if(requested!=null && !requested.equals(((Number)previous).intValue()))throw new IllegalArgumentException("元数据版本已变化，请刷新后再提交");}
    private void stable(Object requested,Object previous){if(requested!=null && !Objects.toString(requested,"").trim().equals(Objects.toString(previous,"").trim()))throw new AccessDeniedException("元数据标识和父级不能通过编辑迁移，请登记新的对象");}
    public void database(Long id){database(id,"read");}
    public void database(Long id,String action){if(id==null || !databaseIds(action).contains(id))throw denied();}
    private static Long number(Object value){return value==null?null:((Number)value).longValue();}
    private static String marks(int n){return String.join(",",Collections.nCopies(n,"?"));}
    private static AccessDeniedException denied(){return new AccessDeniedException("元数据不存在或不属于当前可访问项目");}
}
