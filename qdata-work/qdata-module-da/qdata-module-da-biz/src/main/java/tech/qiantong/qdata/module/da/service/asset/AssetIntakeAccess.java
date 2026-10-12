package tech.qiantong.qdata.module.da.service.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.core.domain.BaseEntity;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.da.controller.admin.asset.vo.*;
import tech.qiantong.qdata.module.da.controller.admin.assetColumn.vo.*;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.api.vo.DaAssetApiParamSaveReqVO;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;

/** User initiated registration and imports share project, identity and audit checks. */
@Service
@RequiredArgsConstructor
public class AssetIntakeAccess {
    private final JdbcTemplate jdbc;
    private final DataResourceAccess data;
    private final ProjectPermissionService permissions;
    private final AssetMutationAccess mutations;
    private final AssetReferenceGuard references;

    public List<Map<String,Object>> projects(){
        return jdbc.queryForList("SELECT id,name,code FROM att_project WHERE valid_flag='1' AND del_flag='0' ORDER BY id").stream()
                .filter(p->data.visibleProjectIds().contains(number(p.get("id"))) && permissions.has(number(p.get("id")),"da:asset:add")).toList();
    }
    private String project(Long id,String permission){
        if(id==null || !data.visibleProjectIds().contains(id) || !permissions.has(id,permission))throw denied();
        return jdbc.queryForObject("SELECT code FROM att_project WHERE id=? AND valid_flag='1' AND del_flag='0'",String.class,id);
    }
    public Set<Long> fileInspection(Long projectId, Long assetId){
        if(assetId==null){project(projectId,"da:asset:add");return Set.of(projectId);}
        if(!"6".equals(mutations.asset(assetId,"da:asset:edit").get("type")))throw new IllegalArgumentException("请选择文件资产");
        return mutations.registrationOwners(assetId);
    }
    public void create(DaAssetSaveReqVO request){
        if(request.getId()!=null || request.getDatasetId()!=null)throw new IllegalArgumentException("新增资产不能指定已有资产或受管数据集ID");
        request.setProjectCode(project(request.getProjectId(),"da:asset:add"));
        if(request.getName()==null || request.getName().isBlank())throw new IllegalArgumentException("资产名称不能为空");
        if(!Set.of("1","2","3","4","5","6","7").contains(Objects.toString(request.getType(),"")))throw new IllegalArgumentException("请选择有效资产类型");
        if(!Set.of("1","2").contains(Objects.toString(request.getCreateType(),"")))throw new IllegalArgumentException("请选择登记类型");
        if(request.getStatus()!=null && !Set.of("1","2").contains(request.getStatus()))throw new IllegalArgumentException("请选择有效发布状态");
        if(request.getStatus()==null)request.setStatus("1");
        if("1".equals(request.getCreateType())){
            if(request.getTableId()!=null)throw new IllegalArgumentException("逻辑资产不能指定元数据表");
            request.setDatasourceId("-1");request.setDatabaseName(null);request.setTableName("-1");
        }else if("1".equals(request.getType())){
            mutations.metadataTarget(request,Set.of(request.getProjectId()));
            // Linking an existing managed table can update its metadata ID, so require edit as well.
            for(Long id:jdbc.queryForList("SELECT id FROM da_asset WHERE datasource_id=? AND database_name=? AND table_name=? AND dataset_id IS NOT NULL AND del_flag='0'",Long.class,request.getDatasourceId(),request.getDatabaseName(),request.getTableName()))mutations.asset(id,"da:asset:edit");
        }else if("7".equals(request.getType())){
            Long source=number(request.getDatasourceId());data.requireDatasourceConfiguration(source);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM da_datasource_project_rel WHERE datasource_id=? AND project_id=? AND valid_flag='1'",Long.class,source,request.getProjectId())==0)throw denied();
        }
        List<BaseEntity> children=Arrays.asList(request.getDaAssetApi(),request.getDaAssetGis(),request.getDaAssetGeo(),request.getDaAssetVideo(),request.getDaAssetFiles());
        for(BaseEntity child:children)if(child!=null){var bean=new BeanWrapperImpl(child);if(bean.getPropertyValue("id")!=null || bean.getPropertyValue("assetId")!=null)throw denied();audit(child);}
        if("2".equals(request.getCreateType())){
            Object required=switch(request.getType()){case "2"->request.getDaAssetApi();case "3"->request.getDaAssetGis();case "4"->request.getDaAssetGeo();case "5"->request.getDaAssetVideo();case "6"->request.getDaAssetFiles();case "7"->request.getFileInfo();default->Boolean.TRUE;};
            if(required==null)throw new IllegalArgumentException("请使用资产登记表单填写完整资源配置");
        }
        params(request.getDaAssetApiParamList());request.setSource("3");request.setSourceType("1");request.setDataCount(0L);request.setFieldCount(0L);audit(request);
    }
    private void params(List<DaAssetApiParamSaveReqVO> rows){if(rows==null)return;for(var row:rows){if(row.getId()!=null || row.getApiId()!=null || row.getParentId()!=null && row.getParentId()>0)throw denied();audit(row);params(row.getDaAssetApiParamList());}}
    public void batch(List<DaAssetSaveReqVO> rows){
        if(rows==null || rows.isEmpty())throw new IllegalArgumentException("导入数据不能为空");
        Set<Long> metadata=new HashSet<>();for(var row:rows){create(row);if(row.getTableId()!=null && !metadata.add(row.getTableId()))throw new IllegalArgumentException("同批次不能重复登记元数据表");}
    }
    public List<DaAssetSaveReqVO> assets(List<DaAssetRespVO> rows,boolean update){
        if(rows==null || rows.isEmpty())throw new IllegalArgumentException("导入数据不能为空");
        List<DaAssetSaveReqVO> requests=new ArrayList<>();Set<Long> ids=new HashSet<>();
        for(var row:rows){var request=BeanUtils.toBean(row,DaAssetSaveReqVO.class);
            if(update){
                if(request.getId()==null || !ids.add(request.getId()))throw new IllegalArgumentException("更新导入须提供不重复的资产ID");
                var stored=mutations.asset(request.getId(),"da:asset:import");if(request.getCreateType()!=null && !Objects.equals(request.getCreateType(),stored.get("create_type")))throw new IllegalArgumentException("Excel不能变更登记类型，请使用资源绑定表单");mutations.edit(request);references.requireStableIdentity(request);
                // Flat spreadsheets update catalog properties; nested resources use their own forms.
                request.setType(null);request.setDaAssetApi(null);request.setDaAssetApiParamList(null);request.setDaAssetGis(null);request.setDaAssetGeo(null);request.setDaAssetVideo(null);request.setDaAssetFiles(null);
            }else{project(request.getProjectId(),"da:asset:import");create(request);}
            requests.add(request);
        }
        if(!update)batch(requests);return requests;
    }
    public List<DaAssetColumnSaveReqVO> columns(List<DaAssetColumnRespVO> rows,boolean update){
        if(rows==null || rows.isEmpty())throw new IllegalArgumentException("导入数据不能为空");
        List<DaAssetColumnSaveReqVO> requests=new ArrayList<>();Set<String> identities=new HashSet<>();
        for(var row:rows){var request=BeanUtils.toBean(row,DaAssetColumnSaveReqVO.class);
            if(update){if(request.getId()==null || !identities.add("id:"+request.getId()))throw new IllegalArgumentException("更新导入须提供不重复的字段ID");Long owner=mutations.column(request.getId(),"import");mutations.columnEdit(request);references.requireStableColumn(request);}
            else{
                if(request.getId()!=null)throw new IllegalArgumentException("新增字段不能指定已有ID");Long owner=number(request.getAssetId());mutations.asset(owner,"da:assetColumn:import");mutations.asset(owner,"da:assetColumn:add");references.requireManualColumnAddition(owner);
                if(request.getColumnName()==null || request.getColumnName().isBlank() || request.getColumnType()==null || request.getColumnType().isBlank())throw new IllegalArgumentException("字段名及类型不能为空");
                String key=owner+":"+request.getColumnName().toLowerCase(Locale.ROOT);if(!identities.add(key) || jdbc.queryForObject("SELECT COUNT(*) FROM da_asset_column WHERE asset_id=? AND column_name=? AND del_flag='0'",Long.class,owner,request.getColumnName())>0)throw new IllegalArgumentException("同一资产不能重复新增字段");audit(request);
            }
            requests.add(request);
        }
        return requests;
    }
    public List<Map<String,Object>> metadata(Long projectId,Long assetId,String search){
        Set<Long> owners;if(assetId!=null)owners=mutations.registrationOwners(assetId);else{project(projectId,"da:asset:add");owners=Set.of(projectId);}
        if(owners.isEmpty())throw denied();List<Map<String,Object>> result=new ArrayList<>();
        List<Object> arguments=new ArrayList<>(owners);arguments.add(owners.size());
        var rows=jdbc.queryForList("SELECT t.id,COALESCE(t.datasource_id,d.datasource_id) datasourceId,COALESCE(t.db_name,d.db_name) databaseName,t.table_name tableName,t.table_comment tableComment,s.datasource_name datasourceName,s.datasource_type datasourceType FROM mc_table t JOIN mc_db d ON d.id=t.db_id JOIN da_datasource s ON s.id=COALESCE(t.datasource_id,d.datasource_id) WHERE t.del_flag='0' AND d.del_flag='0' AND s.del_flag='0' AND s.id IN (SELECT datasource_id FROM da_datasource_project_rel WHERE valid_flag='1' AND project_id IN ("+String.join(",",Collections.nCopies(owners.size(),"?"))+") GROUP BY datasource_id HAVING COUNT(DISTINCT project_id)=?) ORDER BY t.id",arguments.toArray());
        String query=Objects.toString(search,"").toLowerCase(Locale.ROOT);
        for(var row:rows){if(!Objects.toString(row.get("databaseName"),"").concat(".").concat(Objects.toString(row.get("tableName"),"")).toLowerCase(Locale.ROOT).contains(query))continue;
            var request=new DaAssetSaveReqVO();request.setTableId(number(row.get("id")));try{mutations.metadataTarget(request,owners);}catch(AccessDeniedException | IllegalArgumentException excluded){continue;}
            result.add(row);if(result.size()==200)break;
        }
        return result;
    }
    private static void audit(BaseEntity request){request.setCreatorId(SecurityUtils.getUserId());request.setCreateBy(SecurityUtils.getUsername());request.setCreateTime(new Date());request.setUpdatorId(SecurityUtils.getUserId());request.setUpdateBy(SecurityUtils.getUsername());request.setUpdateTime(new Date());}
    private static Long number(Object value){try{return value==null?null:Long.valueOf(String.valueOf(value));}catch(NumberFormatException invalid){throw denied();}}
    private static AccessDeniedException denied(){return new AccessDeniedException("资产登记或导入功能未授权给目标项目，或资源归属不一致");}
}
