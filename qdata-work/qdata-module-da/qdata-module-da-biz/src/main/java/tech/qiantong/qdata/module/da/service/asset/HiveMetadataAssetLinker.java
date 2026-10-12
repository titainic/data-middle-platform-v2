package tech.qiantong.qdata.module.da.service.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.qiantong.qdata.common.database.core.HiveTableIdentity;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.service.datasource.HiveNamespaceBrowser;
import java.util.*;

/** Attach collected metadata to a registered warehouse asset without replacing fields or references. */
@Service
@RequiredArgsConstructor
public class HiveMetadataAssetLinker {
    private final JdbcTemplate jdbc;
    private final HiveNamespaceBrowser namespaces;

    @Transactional(rollbackFor = Exception.class)
    public Long linkRegisteredTable(Long metadataId) {
        if (metadataId == null) return null;
        var metadata = jdbc.queryForList("SELECT t.id,t.datasource_id,t.db_name,t.table_name,d.datasource_type FROM mc_table t JOIN da_datasource d ON d.id=t.datasource_id WHERE t.id=? AND t.del_flag='0' AND d.del_flag='0'", metadataId);
        require(metadata.size() == 1, "元数据表或数据连接不存在");
        var table = metadata.get(0);
        if (!"Hive".equalsIgnoreCase(String.valueOf(table.get("datasource_type")))) return null;
        long sourceId = ((Number)table.get("datasource_id")).longValue();
        var identity = HiveTableIdentity.resolve(String.valueOf(table.get("table_name")), String.valueOf(table.get("db_name")));
        namespaces.authorizeTable(sourceId, identity.canonical());
        var registered = jdbc.queryForList("SELECT m.model_id,m.dataset_id FROM dp_warehouse_materialization m JOIN dp_warehouse_engine e ON e.id=m.engine_id WHERE e.datasource_id=? AND m.database_name=? AND m.table_name=? FOR UPDATE", sourceId, identity.databaseName(), identity.tableName());
        if (registered.isEmpty()) return null;
        require(registered.size() == 1, "物理表登记存在歧义，请先核对数仓身份");
        var assets = jdbc.queryForList("SELECT id,dataset_id,table_id FROM da_asset WHERE datasource_id=? AND database_name=? AND table_name=? AND del_flag='0' FOR UPDATE", sourceId, identity.databaseName(), identity.tableName());
        require(assets.size() == 1, "受管物理表须有唯一已物化资产，请先完成模型物化或核对重复登记");
        var asset = assets.get(0);
        require(Objects.equals(asset.get("dataset_id"), registered.get(0).get("dataset_id")), "资产与模型的数据集身份不一致");
        long assetId = ((Number)asset.get("id")).longValue();
        Long linked = metadataId(asset.get("table_id"));
        require(linked == null || linked.equals(metadataId), "资产已关联其他元数据版本，须保留原引用并核对差异");
        var actual = jdbc.queryForList("SELECT column_name,column_type FROM mc_column WHERE table_id=? AND del_flag='0' ORDER BY id", metadataId);
        var existing = jdbc.queryForList("SELECT column_name,column_type FROM da_asset_column WHERE asset_id=? AND del_flag='0' ORDER BY id", assetId);
        requireSameSchema(actual, existing);
        if (linked == null) {
            int changed = jdbc.update("UPDATE da_asset SET table_id=?,update_by=?,updater_id=?,update_time=NOW() WHERE id=? AND table_id IS NULL", metadataId, SecurityUtils.getUsername(), SecurityUtils.getUserId(), assetId);
            require(changed == 1, "资产关联发生并发变更，请刷新后核对");
        }
        return assetId;
    }

    static void requireSameSchema(List<Map<String,Object>> metadata, List<Map<String,Object>> assets) {
        require(!metadata.isEmpty() && schema(metadata).equals(schema(assets)), "元数据与已有资产字段或类型不一致，未创建或替换字段");
    }
    static Long metadataId(Object value) {
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }
    private static Map<String,String> schema(List<Map<String,Object>> columns) {
        Map<String,String> result = new TreeMap<>();
        for (var column : columns) {
            String name = HiveTableIdentity.identifier(String.valueOf(column.get("column_name")));
            String type = String.valueOf(column.get("column_type")).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            require(!type.isBlank() && !"NULL".equals(type), "字段类型缺失");
            require(result.put(name, type) == null, "字段名称重复");
        }
        return result;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
