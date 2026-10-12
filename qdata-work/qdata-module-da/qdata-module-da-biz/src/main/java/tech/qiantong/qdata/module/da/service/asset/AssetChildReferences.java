package tech.qiantong.qdata.module.da.service.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.core.domain.BaseEntity;
import java.util.*;

/** Prevent public and composite catalog edits from changing an active service dependency. */
@Service
@RequiredArgsConstructor
public class AssetChildReferences {
    private final JdbcTemplate jdbc;
    public Long reserveApiSlot(Long asset,Long supplied){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("API绑定必须在事务内完成");
        if(asset==null || jdbc.queryForList("SELECT id FROM da_asset WHERE id=? AND type='2' AND del_flag='0' FOR UPDATE",Long.class,asset).size()!=1)throw new IllegalArgumentException("API父资产不存在");
        var ids=jdbc.queryForList("SELECT id FROM da_asset_api FORCE INDEX(idx_da_api_asset_scope) WHERE asset_id=? AND del_flag='0' FOR UPDATE",Long.class,asset);
        if(ids.size()>1)throw new IllegalStateException("资产存在多个API绑定，请先核对");Long existing=ids.isEmpty()?null:ids.get(0);
        if(supplied!=null && !supplied.equals(existing))throw new IllegalArgumentException("API绑定发生变化，请刷新");return existing;
    }
    private boolean used(String kind,Long id){String transmit="API".equals(kind)?"1":"GIS".equals(kind)?"2":null;return transmit!=null && jdbc.queryForObject("SELECT COUNT(*) FROM ds_api WHERE api_service_type='3' AND transmit_type=? AND api_id=? AND del_flag='0'",Long.class,transmit,String.valueOf(id))>0;}
    public void stable(String kind,BaseEntity request){
        if(!Set.of("API","GIS").contains(kind))return;var bean=new BeanWrapperImpl(request);Long id=(Long)bean.getPropertyValue("id");if(id==null || !used(kind,id))return;
        var row=jdbc.queryForMap("SELECT * FROM da_asset_"+kind.toLowerCase(Locale.ROOT)+" WHERE id=? AND del_flag='0'",id);
        Map<String,String> fields=new LinkedHashMap<>(Map.of("assetId","asset_id","url","url","httpMethod","http_method"));if(kind.equals("GIS")){fields.put("type","type");fields.put("coordinateSystem","coordinate_system");}
        for(var field:fields.entrySet()){Object next=bean.getPropertyValue(field.getKey());if(next!=null && !Objects.toString(next).equals(Objects.toString(row.get(field.getValue()),null)))throw new IllegalArgumentException("资源已被数据服务引用，请通过新版本变更调用配置");}
    }
    public void removable(String kind,Long id){
        if(used(kind,id))throw new IllegalArgumentException("资源仍被数据服务引用，不能删除");
        if(kind.equals("API") && jdbc.queryForObject("SELECT COUNT(*) FROM da_asset_api_param WHERE api_id=? AND del_flag='0'",Long.class,id)>0)throw new IllegalArgumentException("API仍有参数记录，不能产生孤立参数");
    }
    public void parameterChange(Long api,BaseEntity request){
        if(!used("API",api))return;
        if(request==null)throw new IllegalArgumentException("API参数被数据服务引用，请通过新版本变更");
        var bean=new BeanWrapperImpl(request);var row=jdbc.queryForMap("SELECT * FROM da_asset_api_param WHERE id=? AND del_flag='0'",bean.getPropertyValue("id"));
        for(var pair:Map.of("name","name","type","type","parentId","parent_id","columnType","column_type","requestFlag","request_flag","defaultValue","default_value").entrySet()){
            Object next=bean.getPropertyValue(pair.getKey());if("parentId".equals(pair.getKey()) && (next==null || "0".equals(String.valueOf(next))) && (row.get("parent_id")==null || "0".equals(String.valueOf(row.get("parent_id")))))continue;if(next!=null && !Objects.toString(next).equals(Objects.toString(row.get(pair.getValue()),null)))throw new IllegalArgumentException("参数契约已被服务引用，请通过新版本变更");
        }
    }
    public void replaceParameters(Long api,boolean incomingEmpty){
        if(used("API",api) && (!incomingEmpty || jdbc.queryForObject("SELECT COUNT(*) FROM da_asset_api_param WHERE api_id=? AND del_flag='0'",Long.class,api)>0))throw new IllegalArgumentException("被服务引用的参数不能重建ID，请通过新版本变更");
    }
}
