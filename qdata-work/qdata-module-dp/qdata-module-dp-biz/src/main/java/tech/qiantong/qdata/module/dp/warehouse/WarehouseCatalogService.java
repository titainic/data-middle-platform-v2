package tech.qiantong.qdata.module.dp.warehouse;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.database.core.HiveTableIdentity;
import tech.qiantong.qdata.module.da.api.service.asset.IDaAssetApiOutService;
import java.util.*;
import static tech.qiantong.qdata.module.dp.warehouse.WarehouseSql.require;

/** Authorized bridge from a fixed batch to metadata and its original asset identities. */
@Service
@RequiredArgsConstructor
public class WarehouseCatalogService {
    private final WarehouseBatchRegistry batches;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final IDaAssetApiOutService assets;

    public List<Map<String,Object>> catalog(String batchId) {
        batches.get(batchId); // Resource authorization precedes every identity lookup.
        var models = jdbc.queryForList("SELECT s.model_id,s.dataset_id,m.datasource_id,m.database_name,m.model_name FROM dp_warehouse_batch_step s JOIN dp_model m ON m.id=s.model_id WHERE s.batch_id=? ORDER BY s.ordinal", batchId);
        List<Map<String,Object>> result = new ArrayList<>();
        for (var model : models) {
            var tables = jdbc.queryForList("SELECT id,column_count,row_count FROM mc_table WHERE datasource_id=? AND db_name=? AND table_name=? AND master_flag='1' AND del_flag='0'", model.get("datasource_id"), model.get("database_name"), model.get("model_name"));
            var registered = jdbc.queryForList("SELECT id,table_id,dataset_id FROM da_asset WHERE datasource_id=? AND database_name=? AND table_name=? AND del_flag='0'", model.get("datasource_id"), model.get("database_name"), model.get("model_name"));
            require(tables.size() <= 1 && registered.size() <= 1, "元数据或资产物理身份重复，须先核对");
            if (!registered.isEmpty()) require(Objects.equals(registered.get(0).get("dataset_id"), model.get("dataset_id")), "资产与批次数据集身份不一致");
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("modelId", model.get("model_id")); row.put("datasetId", model.get("dataset_id"));
            row.put("table", HiveTableIdentity.resolve(String.valueOf(model.get("model_name")), String.valueOf(model.get("database_name"))).canonical());
            row.put("metadataId", tables.isEmpty() ? null : tables.get(0).get("id"));
            row.put("assetId", registered.isEmpty() ? null : registered.get(0).get("id"));
            row.put("linked", !tables.isEmpty() && !registered.isEmpty() && Objects.equals(String.valueOf(tables.get(0).get("id")), String.valueOf(registered.get(0).get("table_id"))));
            result.add(row);
        }
        return result;
    }

    public List<Map<String,Object>> link(String batchId) {
        require("SUCCESS".equals(batches.get(batchId).get("status")), "仅已成功计算的批次可以关联治理目录");
        return transaction.execute(status -> {
            var rows = catalog(batchId);
            require(!rows.isEmpty() && rows.stream().allMatch(r -> r.get("metadataId") != null && r.get("assetId") != null), "请先完成本批全部物理版本的元数据采集与模型资产登记");
            for (var row : rows) {
                Long linked = assets.linkRegisteredMetadata(((Number)row.get("metadataId")).longValue());
                require(linked != null && linked.equals(((Number)row.get("assetId")).longValue()), "治理目录关联结果不一致");
            }
            return catalog(batchId);
        });
    }
}
