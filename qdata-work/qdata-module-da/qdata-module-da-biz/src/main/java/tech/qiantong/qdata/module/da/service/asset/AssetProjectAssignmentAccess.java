package tech.qiantong.qdata.module.da.service.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.projectRel.vo.*;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;

/** Public asset-sharing operations must be authorized by existing and target projects. */
@Service
@RequiredArgsConstructor
public class AssetProjectAssignmentAccess {
    private final JdbcTemplate jdbc;
    private final DataResourceAccess data;
    private final AssetProjectReadAccess read;
        private final ProjectPermissionService permissions;
    private static final String PREFIX="da:assetProjectRel:";
    private Map<String,Object> asset(Long id){if(id==null)throw denied();var rows=jdbc.queryForList("SELECT * FROM da_asset WHERE id=? AND del_flag='0' FOR UPDATE",id);if(rows.size()!=1)throw denied();data.requireAsset(id);return rows.get(0);}
    private Map<String,Object> relation(Long id){if(id==null)throw denied();var rows=jdbc.queryForList("SELECT * FROM da_asset_project_rel WHERE id=? AND del_flag='0'",id);if(rows.size()!=1)throw denied();return rows.get(0);}
    private Set<Long> owners(Long id){return new HashSet<>(jdbc.queryForList("SELECT project_id FROM da_asset_project_rel FORCE INDEX(idx_da_asset_project_membership) WHERE asset_id=? AND valid_flag='1' AND del_flag='0' FOR UPDATE",Long.class,id));}
    private void requireOwnerAction(Map<String,Object> row,String action){
        Long id=number(row.get("id"));if(permissions.isAdministrator())return;
        Set<Long> owners=owners(id);
        if(owners.isEmpty() && row.get("dataset_id")==null && Set.of("add","import").contains(action) && Objects.equals(number(row.get("creator_id")),SecurityUtils.getUserId()))return;
        if(owners.isEmpty() || !owners.stream().allMatch(p->data.visibleProjectIds().contains(p) && permissions.has(p,PREFIX+action)))throw denied();
    }
    private String project(Long id,String action){
        if(id==null || !data.visibleProjectIds().contains(id) || !permissions.has(id,PREFIX+action))throw denied();
        return jdbc.queryForObject("SELECT code FROM att_project WHERE id=? AND valid_flag='1' AND del_flag='0'",String.class,id);
    }
    private void target(Map<String,Object> asset,Long project){
        if(asset.get("dataset_id")!=null){var projects=jdbc.queryForList("SELECT s.project_id FROM dp_warehouse_dataset d JOIN dp_warehouse_space s ON s.id=d.space_id WHERE d.id=?",Long.class,asset.get("dataset_id"));if(!projects.equals(List.of(project)))throw new AccessDeniedException("受管资产归属由数仓空间维护");}
        Long source=number(asset.get("datasource_id"));
        if("1".equals(asset.get("type")) && source!=null && source>0){
            if(jdbc.queryForObject("SELECT COUNT(*) FROM da_datasource_project_rel WHERE datasource_id=? AND project_id=? AND valid_flag='1'",Long.class,source,project)==0)throw denied();
            String table=Objects.toString(asset.get("table_name"),"");String database=Objects.toString(asset.get("database_name"),"");if(!database.isBlank())table=database+"."+table;
            var physical=data.owningHiveProjects(source,table);if(!physical.isEmpty() && !physical.contains(project))throw new AccessDeniedException("不能通过资产关系扩大Hive命名空间权限");
        }
    }
    public List<Long> ids(String action){var assets=read.ids(PREFIX+action);if(assets.isEmpty())return List.of(-1L);var ids=jdbc.queryForList("SELECT id FROM da_asset_project_rel WHERE del_flag='0' AND asset_id IN ("+String.join(",",Collections.nCopies(assets.size(),"?"))+")",Long.class,assets.toArray());return ids.isEmpty()?List.of(-1L):ids;}
    public void get(Long id){read.asset(number(relation(id).get("asset_id")),PREFIX+"query");}
    public void create(DaAssetProjectRelSaveReqVO request){
        if(request.getId()!=null)throw new IllegalArgumentException("新增关联不能指定已有ID");var row=asset(request.getAssetId());requireOwnerAction(row,"add");request.setProjectCode(project(request.getProjectId(),"add"));target(row,request.getProjectId());
        request.setCreatorId(SecurityUtils.getUserId());request.setCreateBy(SecurityUtils.getUsername());request.setCreateTime(new Date());auditUpdate(request);
    }
    public void edit(DaAssetProjectRelSaveReqVO request){
        var old=relation(request.getId());Long id=number(old.get("asset_id")),project=number(old.get("project_id"));
        if(request.getAssetId()!=null && !request.getAssetId().equals(id) || request.getProjectId()!=null && !request.getProjectId().equals(project))throw new AccessDeniedException("关联身份不可原地迁移，请新增目标关联后处理原关联");
        var row=asset(id);requireOwnerAction(row,"edit");request.setAssetId(id);request.setProjectId(project);request.setProjectCode(project(project,"edit"));
        request.setCreatorId(number(old.get("creator_id")));request.setCreateBy(Objects.toString(old.get("create_by"),null));request.setCreateTime(jdbc.queryForObject("SELECT create_time FROM da_asset_project_rel WHERE id=?",java.sql.Timestamp.class,request.getId()));auditUpdate(request);
    }
    public void remove(Collection<Long> ids){
        Map<Long,Set<Long>> removed=new HashMap<>();for(Long id:ids){var rel=relation(id);Long asset=number(rel.get("asset_id"));var row=asset(asset);requireOwnerAction(row,"remove");project(number(rel.get("project_id")),"remove");
            if(row.get("dataset_id")!=null)throw new AccessDeniedException("受管资产归属由数仓空间维护");removed.computeIfAbsent(asset,k->new HashSet<>()).add(id);
        }
        for(var entry:removed.entrySet()){var remaining=jdbc.queryForList("SELECT id FROM da_asset_project_rel WHERE asset_id=? AND valid_flag='1' AND del_flag='0'",Long.class,entry.getKey());if(remaining.stream().noneMatch(id->!entry.getValue().contains(id)))throw new IllegalArgumentException("不能移除最后一个有效归属，请通过资产归档处理");}
    }
    public List<DaAssetProjectRelSaveReqVO> imports(List<DaAssetProjectRelRespVO> rows,boolean update){
        if(rows==null || rows.isEmpty())throw new IllegalArgumentException("导入不能为空");List<DaAssetProjectRelSaveReqVO> requests=new ArrayList<>();Set<Long> ids=new HashSet<>();
        for(var row:rows){var request=tech.qiantong.qdata.common.utils.object.BeanUtils.toBean(row,DaAssetProjectRelSaveReqVO.class);if(update){if(request.getId()==null || !ids.add(request.getId()))throw new IllegalArgumentException("更新ID不可重复或为空");edit(request);}else create(request);requireOwnerAction(asset(request.getAssetId()),"import");project(request.getProjectId(),"import");requests.add(request);}return requests;
    }
    private void auditUpdate(DaAssetProjectRelSaveReqVO request){request.setUpdatorId(SecurityUtils.getUserId());request.setUpdateBy(SecurityUtils.getUsername());request.setUpdateTime(new Date());}
    private static Long number(Object value){return value==null?null:Long.valueOf(String.valueOf(value));}
    private static AccessDeniedException denied(){return new AccessDeniedException("资产关联或目标项目未授权，或物理来源不属于目标项目");}
}
