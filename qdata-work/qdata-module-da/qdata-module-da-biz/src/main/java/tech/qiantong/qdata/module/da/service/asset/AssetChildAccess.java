package tech.qiantong.qdata.module.da.service.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.core.domain.BaseEntity;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import java.util.*;

/** Project checks for public child-resource endpoints. Parent composition uses its own authorization. */
@Service
@RequiredArgsConstructor
public class AssetChildAccess {
    public enum Kind {
        API("da_asset_api","da:api:","2"), GIS("da_asset_gis","da:assetGis:","3"),
        GEO("da_asset_geo","da:assetGeo:","4"), VIDEO("da_asset_video","da:assetVideo:","5"),
        FILES("da_asset_files","da:assetFiles:","6"), PARAM("da_asset_api_param","da:assetApiParam:","2");
        final String table,permission,assetType;
        Kind(String table,String permission,String assetType){this.table=table;this.permission=permission;this.assetType=assetType;}
    }
    private final JdbcTemplate jdbc;
    private final AssetProjectReadAccess read;
    private final AssetMutationAccess mutations;
    private final AssetChildReferences references;

    private Map<String,Object> row(Kind kind,Long id){if(id==null)throw denied();var rows=jdbc.queryForList("SELECT * FROM "+kind.table+" WHERE id=? AND del_flag='0'",id);if(rows.size()!=1)throw denied();return rows.get(0);}
    private Long parentAsset(Kind kind,Map<String,Object> row){return kind==Kind.PARAM?number(row(Kind.API,number(row.get("api_id"))).get("asset_id")):number(row.get("asset_id"));}
    private void type(Kind kind,Long asset){if(asset==null || jdbc.queryForObject("SELECT COUNT(*) FROM da_asset WHERE id=? AND type=? AND del_flag='0'",Long.class,asset,kind.assetType)!=1)throw denied();}
    public Long get(Kind kind,Long id,String action){Long asset=parentAsset(kind,row(kind,id));type(kind,asset);read.asset(asset,kind.permission+action);return asset;}
    public void parameterTree(Long api){Long asset=number(row(Kind.API,api).get("asset_id"));type(Kind.PARAM,asset);read.asset(asset,Kind.PARAM.permission+"list");}
    public List<Long> ids(Kind kind,String action){
        var assets=read.ids(kind.permission+action);if(assets.isEmpty())return List.of(-1L);
        String owner=kind==Kind.PARAM?"p.asset_id":"c.asset_id";
        String join=kind==Kind.PARAM?" JOIN da_asset_api p ON p.id=c.api_id AND p.del_flag='0'":"";
        List<Object> args=new ArrayList<>();args.add(kind.assetType);args.addAll(assets);
        var ids=jdbc.queryForList("SELECT c.id FROM "+kind.table+" c"+join+" JOIN da_asset a ON a.id="+owner+" AND a.del_flag='0' WHERE c.del_flag='0' AND a.type=? AND a.id IN ("+String.join(",",Collections.nCopies(assets.size(),"?"))+")",Long.class,args.toArray());
        return ids.isEmpty()?List.of(-1L):ids;
    }
    public void save(Kind kind,BaseEntity request,boolean update){save(kind,request,update,true);}
    private void save(Kind kind,BaseEntity request,boolean update,boolean fullAncestry){
        var bean=new BeanWrapperImpl(request);Long id=number(bean.getPropertyValue("id"));String parent=kind==Kind.PARAM?"apiId":"assetId";
        Map<String,Object> old=null;Long parentId=number(bean.getPropertyValue(parent));
        if(update){old=row(kind,id);Long stored=number(old.get(kind==Kind.PARAM?"api_id":"asset_id"));if(parentId!=null && !parentId.equals(stored))throw denied();parentId=stored;bean.setPropertyValue(parent,parentId);}
        else if(id!=null)throw new IllegalArgumentException("新增子资源不能指定已有ID");
        Long asset=kind==Kind.PARAM?number(row(Kind.API,parentId).get("asset_id")):parentId;type(kind,asset);mutations.asset(asset,kind.permission+(update?"edit":"add"));
        if(!update && kind!=Kind.PARAM && jdbc.queryForObject("SELECT COUNT(*) FROM "+kind.table+" WHERE asset_id=? AND del_flag='0'",Long.class,asset)>0)throw new IllegalArgumentException("资产已有关联资源，请修改原记录");
        if(kind==Kind.PARAM){
            Object nested=bean.getPropertyValue("daAssetApiParamList");if(nested instanceof List<?> children && !children.isEmpty())throw new IllegalArgumentException("独立参数接口逐项保存，请勿携带嵌套记录");
            Long proposed=number(bean.getPropertyValue("parentId"));if(update && proposed==null){proposed=number(old.get("parent_id"));bean.setPropertyValue("parentId",proposed);}
            Set<Long> visited=new HashSet<>();Long current=proposed;
            while(current!=null && current>0){if(Objects.equals(current,id) || !visited.add(current))throw new IllegalArgumentException("参数层级不能形成循环");var ancestor=row(Kind.PARAM,current);if(!Objects.equals(number(ancestor.get("api_id")),parentId))throw denied();if(!fullAncestry)break;current=number(ancestor.get("parent_id"));}
            references.parameterChange(parentId,update?request:null);
        }else if(update)references.stable(kind.name(),request);
        if(update){request.setCreatorId(number(old.get("creator_id")));request.setCreateBy(Objects.toString(old.get("create_by"),null));request.setCreateTime(jdbc.queryForObject("SELECT create_time FROM "+kind.table+" WHERE id=?",java.sql.Timestamp.class,id));}
        else{request.setCreatorId(SecurityUtils.getUserId());request.setCreateBy(SecurityUtils.getUsername());request.setCreateTime(new Date());}
        request.setUpdatorId(SecurityUtils.getUserId());request.setUpdateBy(SecurityUtils.getUsername());request.setUpdateTime(new Date());
    }
    public <T extends BaseEntity> List<T> imports(Kind kind,List<?> rows,boolean update,Class<T> type){
        if(rows==null || rows.isEmpty())throw new IllegalArgumentException("导入数据不能为空");
        List<T> requests=new ArrayList<>();Set<Long> identities=new HashSet<>();Set<Long> parents=new HashSet<>();
        for(Object row:rows){T request=BeanUtils.toBean(row,type);var bean=new BeanWrapperImpl(request);Long id=number(bean.getPropertyValue("id"));
            if(update && (id==null || !identities.add(id)))throw new IllegalArgumentException("更新导入须提供不重复的子资源ID");
            save(kind,request,update,!(kind==Kind.PARAM && update));Long parent=number(bean.getPropertyValue(kind==Kind.PARAM?"apiId":"assetId"));Long asset=kind==Kind.PARAM?number(row(Kind.API,parent).get("asset_id")):parent;mutations.asset(asset,kind.permission+"import");
            if(!update && kind!=Kind.PARAM && !parents.add(parent))throw new IllegalArgumentException("同批次不能重复创建同一资产的资源");requests.add(request);
        }
        // Parameter imports update several existing parents at once; validate the final graph too.
        if(kind==Kind.PARAM && update){Map<Long,Long> proposed=new HashMap<>();for(T request:requests){var bean=new BeanWrapperImpl(request);proposed.put(number(bean.getPropertyValue("id")),number(bean.getPropertyValue("parentId")));}
            for(Long id:proposed.keySet()){Set<Long> seen=new HashSet<>();Long current=id;while(current!=null && current>0){if(!seen.add(current))throw new IllegalArgumentException("导入后的参数层级不能形成循环");current=proposed.containsKey(current)?proposed.get(current):number(row(kind,current).get("parent_id"));}}
        }
        return requests;
    }
    public void remove(Kind kind,Collection<Long> ids){
        if(ids==null || ids.isEmpty())throw new IllegalArgumentException("请选择子资源");Set<Long> selected=new HashSet<>(ids);
        for(Long id:ids){Long asset=parentAsset(kind,row(kind,id));type(kind,asset);mutations.asset(asset,kind.permission+"remove");}
        for(Long id:ids){var row=row(kind,id);
            if(kind==Kind.PARAM){references.parameterChange(number(row.get("api_id")),null);for(Long child:jdbc.queryForList("SELECT id FROM da_asset_api_param WHERE parent_id=? AND del_flag='0'",Long.class,id))if(!selected.contains(child))throw new IllegalArgumentException("参数仍有子节点，请先处理下级关系");}
            else references.removable(kind.name(),id);
        }
    }
    private static Long number(Object value){return value==null?null:Long.valueOf(String.valueOf(value));}
    private static AccessDeniedException denied(){return new AccessDeniedException("子资源不存在、父资产类型或归属不匹配，或所属项目未授权");}
}
