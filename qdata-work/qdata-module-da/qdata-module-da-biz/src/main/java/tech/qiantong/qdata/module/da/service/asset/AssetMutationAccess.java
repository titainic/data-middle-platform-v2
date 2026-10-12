package tech.qiantong.qdata.module.da.service.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.core.domain.BaseEntity;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.da.controller.admin.asset.vo.*;
import tech.qiantong.qdata.module.da.controller.admin.assetColumn.vo.*;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;

/** Existing catalog mutations require the operation in every owning project. */
@Service
@RequiredArgsConstructor
public class AssetMutationAccess {
    private final JdbcTemplate jdbc;
    private final DataResourceAccess data;
    private final ProjectPermissionService permissions;

    private Map<String,Object> row(Long id){if(id==null)throw denied();var rows=jdbc.queryForList("SELECT * FROM da_asset WHERE id=? AND del_flag='0'",id);if(rows.size()!=1)throw denied();return rows.get(0);}
    private Set<Long> owners(Map<String,Object> row){
        if(row.get("dataset_id")!=null)return new HashSet<>(jdbc.queryForList("SELECT s.project_id FROM dp_warehouse_dataset d JOIN dp_warehouse_space s ON s.id=d.space_id WHERE d.id=?",Long.class,row.get("dataset_id")));
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT project_id FROM da_asset_project_rel WHERE asset_id=? AND valid_flag='1' AND del_flag='0'",Long.class,row.get("id")));
    }
    private boolean granted(Long project,String permission){return "da:assetColumn:remove".equals(permission)?permissions.any(project,permission,"da:assetColumn:assetcolumn:remove"):permissions.has(project,permission);}
    private boolean allowed(Map<String,Object> row,String permission){
        if(permissions.isAdministrator())return true;Set<Long> owners=owners(row),visible=data.visibleProjectIds();return !owners.isEmpty() && owners.stream().allMatch(p->visible.contains(p) && granted(p,permission));
    }
    public Map<String,Object> asset(Long id,String permission){data.requireAsset(id);var row=row(id);if(!allowed(row,permission))throw denied();return row;}
    public Map<String,Boolean> capabilities(Long id,String prefix){
        var row=row(id);Map<String,Boolean> result=new LinkedHashMap<>();for(String action:List.of("add","edit","remove"))result.put(action,allowed(row,prefix+action));return result;
    }
    public DaAssetRespVO present(DaAssetRespVO row){if(row!=null)row.setWriteCapabilities(capabilities(row.getId(),"da:asset:"));return row;}
    public DaAssetColumnRespVO present(DaAssetColumnRespVO row){if(row!=null)row.setWriteCapabilities(capabilities(number(row.getAssetId()),"da:assetColumn:"));return row;}
    public void edit(DaAssetSaveReqVO request){
        var row=asset(request.getId(),"da:asset:edit");
        if(request.getDatasetId()!=null && !Objects.equals(request.getDatasetId(),number(row.get("dataset_id"))))throw denied();request.setDatasetId(number(row.get("dataset_id")));
        if(request.getType()!=null && !request.getType().equals(row.get("type")))throw new IllegalArgumentException("资产类型不能原地改变");
        if(request.getProjectId()!=null && !owners(row).contains(request.getProjectId()))throw denied();
        boolean bind="1".equals(row.get("create_type")) && "2".equals(request.getCreateType());
        if(bind)bindingTarget(request,row);
        else{
            same(request.getDatasourceId(),row.get("datasource_id"));same(request.getDatabaseName(),row.get("database_name"));same(request.getTableName(),row.get("table_name"));same(request.getTableId(),row.get("table_id"));same(request.getCreateType(),row.get("create_type"));
        }
        if(request.getStatus()!=null && !Objects.equals(request.getStatus(),row.get("status")) && !Set.of("1","2").contains(request.getStatus()))throw new IllegalArgumentException("不支持的资产发布状态");
        request.setSource(Objects.toString(row.get("source"),null));request.setSourceType(Objects.toString(row.get("source_type"),null));request.setDataCount(number(row.get("data_count")));request.setFieldCount(number(row.get("field_count")));
        audit(request,"da_asset",request.getId(),row);nested(request);
    }
    public void bind(DaAssetSaveReqVO request){
        var row=asset(request.getId(),"da:asset:edit");if(!"1".equals(row.get("create_type")))throw new IllegalArgumentException("已登记资源请通过新版本变更，不能重新绑定");request.setCreateType("2");edit(request);
    }
    public Set<Long> registrationOwners(Long assetId){return owners(asset(assetId,"da:asset:edit"));}
    private void bindingTarget(DaAssetSaveReqVO request,Map<String,Object> asset){
        if(!"1".equals(Objects.toString(asset.get("type"),"")))return;
        metadataTarget(request,owners(asset));
    }
    public void metadataTarget(DaAssetSaveReqVO request,Set<Long> owners){
        if(request.getTableId()==null)throw new IllegalArgumentException("请选择已登记的表元数据");
        var tables=jdbc.queryForList("SELECT COALESCE(t.datasource_id,d.datasource_id) source_id,COALESCE(t.db_name,d.db_name) db_name,t.table_name,t.datasource_id table_source,d.datasource_id db_source FROM mc_table t JOIN mc_db d ON d.id=t.db_id WHERE t.id=? AND t.del_flag='0' AND d.del_flag='0'",request.getTableId());
        if(tables.size()!=1)throw denied();var table=tables.get(0);Long source=number(table.get("source_id"));
        if(table.get("table_source")!=null && table.get("db_source")!=null && !Objects.equals(number(table.get("table_source")),number(table.get("db_source"))))throw denied();
        String database=Objects.toString(table.get("db_name"),""),name=Objects.toString(table.get("table_name"),"");data.requireMetadataTable(source,database+"."+name);
        Set<Long> assigned=new HashSet<>(jdbc.queryForList("SELECT project_id FROM da_datasource_project_rel WHERE datasource_id=? AND valid_flag='1'",Long.class,source));if(!assigned.containsAll(owners))throw denied();
        var hive=data.owningHiveProjects(source,database+"."+name);if(!hive.isEmpty() && (!hive.equals(owners)))throw denied();
        same(request.getDatasourceId(),source);same(request.getDatabaseName(),database);same(request.getTableName(),name);request.setDatasourceId(String.valueOf(source));request.setDatabaseName(database);request.setTableName(name);
    }
    private void nested(DaAssetSaveReqVO r){
        if(r.getDaAssetApi()!=null){var child=r.getDaAssetApi();child("da_asset_api",child.getId(),r.getId(),child);child.setAssetId(r.getId());if(r.getDaAssetApiParamList()!=null)params(r.getDaAssetApiParamList(),child.getId());}
        if(r.getDaAssetGis()!=null){var child=r.getDaAssetGis();child("da_asset_gis",child.getId(),r.getId(),child);child.setAssetId(r.getId());}
        if(r.getDaAssetGeo()!=null){var child=r.getDaAssetGeo();child("da_asset_geo",child.getId(),r.getId(),child);child.setAssetId(r.getId());}
        if(r.getDaAssetVideo()!=null){var child=r.getDaAssetVideo();child("da_asset_video",child.getId(),r.getId(),child);child.setAssetId(r.getId());}
        if(r.getDaAssetFiles()!=null){var child=r.getDaAssetFiles();child("da_asset_files",child.getId(),r.getId(),child);child.setAssetId(r.getId());}
    }
    private void child(String table,Long id,Long asset,BaseEntity request){
        if(id==null){var existing=jdbc.queryForList("SELECT id FROM "+table+" WHERE asset_id=? AND del_flag='0'",Long.class,asset);if(existing.size()>1)throw new IllegalArgumentException("关联记录不唯一，请指定原记录");if(existing.size()==1){id=existing.get(0);new org.springframework.beans.BeanWrapperImpl(request).setPropertyValue("id",id);}}
        if(id==null){request.setCreatorId(SecurityUtils.getUserId());request.setCreateBy(SecurityUtils.getUsername());request.setCreateTime(new java.util.Date());request.setUpdatorId(SecurityUtils.getUserId());request.setUpdateBy(SecurityUtils.getUsername());request.setUpdateTime(new java.util.Date());return;}var rows=jdbc.queryForList("SELECT * FROM "+table+" WHERE id=? AND asset_id=? AND del_flag='0'",id,asset);if(rows.size()!=1)throw denied();audit(request,table,id,rows.get(0));
    }
    private void params(List<tech.qiantong.qdata.module.da.controller.admin.assetchild.api.vo.DaAssetApiParamSaveReqVO> rows,Long api){
        for(var row:rows){if(row.getId()!=null && (api==null || jdbc.queryForObject("SELECT COUNT(*) FROM da_asset_api_param WHERE id=? AND api_id=? AND del_flag='0'",Long.class,row.getId(),api)!=1))throw denied();if(row.getApiId()!=null && !Objects.equals(row.getApiId(),api))throw denied();if(row.getParentId()!=null && row.getParentId()>0 && (api==null || jdbc.queryForObject("SELECT COUNT(*) FROM da_asset_api_param WHERE id=? AND api_id=? AND del_flag='0'",Long.class,row.getParentId(),api)!=1))throw denied();if(row.getDaAssetApiParamList()!=null)params(row.getDaAssetApiParamList(),api);}
    }
    public Long column(Long id,String action){var rows=jdbc.queryForList("SELECT asset_id FROM da_asset_column WHERE id=? AND del_flag='0'",Long.class,id);if(rows.size()!=1)throw denied();Long asset=rows.get(0);asset(asset,"da:assetColumn:"+action);return asset;}
    public void columnEdit(DaAssetColumnSaveReqVO request){
        Long asset=column(request.getId(),"edit");if(request.getAssetId()!=null && !Objects.equals(number(request.getAssetId()),asset))throw denied();request.setAssetId(String.valueOf(asset));
        var row=jdbc.queryForMap("SELECT * FROM da_asset_column WHERE id=?",request.getId());
        same(request.getColumnName(),row.get("column_name"));
        request.setColumnLength(number(row.get("column_length")));request.setColumnScale(number(row.get("column_scale")));request.setNullableFlag(Objects.toString(row.get("nullable_flag"),null));request.setPkFlag(Objects.toString(row.get("pk_flag"),null));request.setDefaultValue(Objects.toString(row.get("default_value"),null));
        if(request.getColumnType()!=null && !request.getColumnType().replaceAll("\\s", "").equalsIgnoreCase(Objects.toString(row.get("column_type"), "").replaceAll("\\s", "")))throw new AccessDeniedException("资产字段类型应通过模型或元数据版本变更");
        if(request.getColumnName()==null)request.setColumnName((String)row.get("column_name"));if(request.getColumnType()==null)request.setColumnType((String)row.get("column_type"));
        if(request.getDataElemCodeFlag()==null){request.setDataElemCodeFlag(Objects.toString(row.get("data_elem_code_flag"),null));if(request.getDataElemCodeId()==null)request.setDataElemCodeId(Objects.toString(row.get("data_elem_code_id"),null));}
        audit(request,"da_asset_column",request.getId(),row);
    }
    private void audit(BaseEntity request,String table,Long id,Map<String,Object> row){request.setCreatorId(number(row.get("creator_id")));request.setCreateBy(Objects.toString(row.get("create_by"),null));request.setCreateTime(jdbc.queryForObject("SELECT create_time FROM "+table+" WHERE id=?",java.sql.Timestamp.class,id));request.setUpdatorId(SecurityUtils.getUserId());request.setUpdateBy(SecurityUtils.getUsername());request.setUpdateTime(new java.util.Date());}
    private static void same(Object requested,Object previous){if(requested!=null && !Objects.toString(requested).equals(Objects.toString(previous,null)))throw new AccessDeniedException("资产资源身份不可通过普通修改迁移");}
    private static Long number(Object value){return value==null?null:Long.valueOf(String.valueOf(value));}
    private static AccessDeniedException denied(){return new AccessDeniedException("资产写入功能未授权给所有归属项目，或关联对象不属于当前资产");}
}
