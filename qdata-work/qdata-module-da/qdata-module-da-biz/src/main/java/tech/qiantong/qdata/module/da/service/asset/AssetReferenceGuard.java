package tech.qiantong.qdata.module.da.service.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.module.da.controller.admin.asset.vo.DaAssetSaveReqVO;
import tech.qiantong.qdata.module.da.controller.admin.assetColumn.vo.DaAssetColumnSaveReqVO;
import java.util.*;

/** Referenced physical identities and column IDs remain stable across catalog operations. */
@Service
@RequiredArgsConstructor
public class AssetReferenceGuard {
    private final JdbcTemplate jdbc;
    public Map<String,Object> references(Long id) {
        var assets=jdbc.queryForList("SELECT id,datasource_id,database_name,table_name,dataset_id,table_id FROM da_asset WHERE id=? AND del_flag='0'",id);
        if(assets.isEmpty())return Map.of("assetId",id,"models",List.of(),"qualityTasks",List.of(),"qualityRules",List.of(),"services",List.of(),"standards",List.of(),"standardLinks",0L,"referenced",false);
        var asset=assets.get(0);
        var models=jdbc.queryForList("SELECT id,model_name name,database_name FROM dp_model WHERE del_flag='0' AND ((? IS NOT NULL AND dataset_id=?) OR (datasource_id=? AND database_name=? AND model_name=?)) ORDER BY id",asset.get("dataset_id"),asset.get("dataset_id"),asset.get("datasource_id"),asset.get("database_name"),asset.get("table_name"));
        var tasks=jdbc.queryForList("SELECT id,task_name name FROM dpp_quality_task WHERE asset_id=? AND del_flag='0' ORDER BY id",id);
        var rules=jdbc.queryForList("SELECT e.id,e.task_id,e.name,e.rule_code,e.rule_name,e.eva_column FROM dpp_quality_task_evaluate e JOIN dpp_quality_task t ON t.id=e.task_id WHERE t.asset_id=? AND t.del_flag='0' AND e.del_flag='0' ORDER BY e.id",id);
        var services=jdbc.queryForList("SELECT DISTINCT a.id,a.name,a.api_version FROM ds_api a LEFT JOIN dp_warehouse_serving_product p ON p.api_id=a.id WHERE a.del_flag='0' AND ((? IS NOT NULL AND p.dataset_id=?) OR (JSON_VALID(a.config_json) AND JSON_UNQUOTE(JSON_EXTRACT(a.config_json,'$.sourceId'))=? AND JSON_UNQUOTE(JSON_EXTRACT(a.config_json,'$.dbName'))=? AND JSON_UNQUOTE(JSON_EXTRACT(a.config_json,'$.tableName'))=?) OR (a.api_service_type='3' AND ((a.transmit_type='1' AND EXISTS(SELECT 1 FROM da_asset_api child WHERE child.asset_id=? AND child.del_flag='0' AND CAST(child.id AS CHAR)=a.api_id)) OR (a.transmit_type='2' AND EXISTS(SELECT 1 FROM da_asset_gis child WHERE child.asset_id=? AND child.del_flag='0' AND CAST(child.id AS CHAR)=a.api_id))))) ORDER BY a.id",asset.get("dataset_id"),asset.get("dataset_id"),Objects.toString(asset.get("datasource_id"),null),asset.get("database_name"),asset.get("table_name"),id,id);
        Long standards=jdbc.queryForObject("SELECT COUNT(*) FROM dp_data_elem_asset_rel WHERE asset_id=? AND del_flag='0'",Long.class,String.valueOf(id));
        var standardRows=jdbc.queryForList("SELECT id,data_elem_type,data_elem_id,column_id,column_name FROM dp_data_elem_asset_rel WHERE asset_id=? AND del_flag='0' ORDER BY id",String.valueOf(id));
        return Map.of("assetId",id,"models",models,"qualityTasks",tasks,"qualityRules",rules,"services",services,"standards",standardRows,"standardLinks",standards,"referenced",!models.isEmpty() || !tasks.isEmpty() || !rules.isEmpty() || !services.isEmpty() || standards>0);
    }
    public void requireRemovable(Collection<Long> ids) {
        if(ids==null || ids.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("请选择有效资产");
        for(Long id:ids)if(Boolean.TRUE.equals(references(id).get("referenced")))throw new IllegalArgumentException("资产 "+id+" 被模型、质量、标准或服务引用，请先查看影响分析；已有引用和数据保留");
    }
    public void requireStableIdentity(DaAssetSaveReqVO request) {
        if(request.getId()==null)return;
        var before=jdbc.queryForMap("SELECT datasource_id,database_name,table_name,table_id FROM da_asset WHERE id=?",request.getId());
        boolean changed=changed(request.getDatasourceId(),before.get("datasource_id")) || changed(request.getDatabaseName(),before.get("database_name")) || changed(request.getTableName(),before.get("table_name")) || changed(request.getTableId(),before.get("table_id"));
        if(changed && Boolean.TRUE.equals(references(request.getId()).get("referenced")))throw new IllegalArgumentException("被引用资产的物理身份不可原地改变，请登记新的数据版本");
    }
    public Long columnAsset(Long id){var rows=jdbc.queryForList("SELECT asset_id FROM da_asset_column WHERE id=? AND del_flag='0'",Long.class,id);if(rows.size()!=1)throw new IllegalArgumentException("资产字段不存在");return rows.get(0);}
    public void requireColumnsRemovable(Collection<Long> ids) {
        for(Long id:ids) {
            Long asset=columnAsset(id);Long standards=jdbc.queryForObject("SELECT COUNT(*) FROM dp_data_elem_asset_rel WHERE column_id=? AND del_flag='0'",Long.class,String.valueOf(id));
            if(standards>0 || Boolean.TRUE.equals(references(asset).get("referenced")))throw new IllegalArgumentException("字段被模型、标准、质量或服务引用，不能删除其元数据标识");
        }
    }
    public void requireStableColumn(DaAssetColumnSaveReqVO request) {
        if(request.getId()==null)return;
        var before=jdbc.queryForMap("SELECT asset_id,column_name,column_type,column_length,column_scale FROM da_asset_column WHERE id=? AND del_flag='0'",request.getId());
        if(changed(request.getAssetId(),before.get("asset_id")))throw new IllegalArgumentException("字段不能移动到其他资产");
        boolean changed=changed(request.getColumnName(),before.get("column_name")) || typeChanged(request.getColumnType(),before.get("column_type")) || changed(request.getColumnLength(),before.get("column_length")) || changed(request.getColumnScale(),before.get("column_scale"));
        if(changed && Boolean.TRUE.equals(references(Long.valueOf(String.valueOf(before.get("asset_id")))).get("referenced")))throw new IllegalArgumentException("被引用字段的名称、类型和精度不可原地改变，请创建新的模型版本");
    }
    public void requireManualColumnAddition(Long assetId) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM da_asset WHERE id=? AND dataset_id IS NOT NULL AND del_flag='0'",Long.class,assetId)>0)throw new IllegalArgumentException("受管数仓字段由模型版本生成，请从建模流程新增字段");
    }
    private static boolean changed(Object next,Object previous){return next!=null && !String.valueOf(next).equals(Objects.toString(previous,null));}
    private static boolean typeChanged(String next,Object previous){return next!=null && !next.replaceAll("\\s","").equalsIgnoreCase(Objects.toString(previous,"").replaceAll("\\s",""));}
}
