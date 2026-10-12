package tech.qiantong.qdata.module.da.service.asset.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import tech.qiantong.qdata.module.dp.api.model.dto.DpModelRespDTO;
import tech.qiantong.qdata.neo4j.dto.LineageDTO;
import tech.qiantong.qdata.neo4j.node.TableNode;
import java.util.*;
import java.util.function.Function;

/** Upstream lineage from registered warehouse definitions; no graph-server dependency. */
final class WarehouseAssetLineage {
    static LineageDTO build(DpModelRespDTO root, String database, Function<Long, DpModelRespDTO> lookup) {
        Map<Long,TableNode> tables = new LinkedHashMap<>();
        List<JSONObject> edges = new ArrayList<>();
        Long rootSpace=JSON.parseObject(root.getWarehouseConfig()).getLong("spaceId");
        Function<Long,DpModelRespDTO> checkedLookup = id -> {
            DpModelRespDTO source = lookup.apply(id);
            if (source != null && !Objects.equals(root.getDatasourceId(), source.getDatasourceId()))
                throw new IllegalArgumentException("上游模型的数据源与当前数仓不一致");
            if (source != null && source.getWarehouseConfig()!=null && !source.getWarehouseConfig().isBlank()
                    && !Objects.equals(rootSpace,JSON.parseObject(source.getWarehouseConfig()).getLong("spaceId")))
                throw new IllegalArgumentException("上游模型不属于相同数仓空间");
            return source;
        };
        visit(root, database, checkedLookup, tables, edges, new HashSet<>());
        LineageDTO result = new LineageDTO();
        result.setCurrentTable(tables.get(root.getId()));
        result.setTables(new ArrayList<>(tables.values()));
        result.setTasks(List.of());
        result.setRels(edges);
        return result;
    }

    private static void visit(DpModelRespDTO model, String database, Function<Long,DpModelRespDTO> lookup,
                              Map<Long,TableNode> tables, List<JSONObject> edges, Set<Long> path) {
        if (model == null || model.getWarehouseConfig() == null || model.getWarehouseConfig().isBlank())
            throw new IllegalArgumentException("已登记的上游数仓模型缺失，无法生成完整血缘");
        Long id = model.getId();
        if (path.contains(id)) throw new IllegalArgumentException("数仓模型血缘存在循环");
        if (tables.containsKey(id)) return;
        JSONObject config = JSON.parseObject(model.getWarehouseConfig());
        String table = config.getString("tableName");
        String modelDatabase = config.getString("databaseName");
        if (modelDatabase == null || modelDatabase.isBlank()) modelDatabase = model.getDatabaseName();
        if (modelDatabase == null || modelDatabase.isBlank()) modelDatabase = database;
        var identity = new tech.qiantong.qdata.common.database.core.HiveTableIdentity(modelDatabase, table);
        tables.put(id, TableNode.builder().id(id).name(identity.canonical()).tableName(identity.canonical())
                .dbName(identity.databaseName()).datasourceType("Hive").datasourceName(model.getDatasourceName()).build());
        path.add(id);
        Set<Long> upstream = new LinkedHashSet<>();
        Set<Long> dimensionIds = new HashSet<>();
        if (config.getJSONArray("upstreamIds") != null)
            upstream.addAll(config.getJSONArray("upstreamIds").toJavaList(Long.class));
        if (config.getJSONArray("dimensions") != null)
            for (Object value : config.getJSONArray("dimensions")) {
                Long dimensionId = ((JSONObject)value).getLong("modelId");
                if (dimensionId != null) { upstream.add(dimensionId); dimensionIds.add(dimensionId); }
            }
        for (Long parent : upstream) {
            visit(lookup.apply(parent), database, lookup, tables, edges, path);
            JSONObject edge = new JSONObject();
            edge.put("startNodeId",parent);edge.put("endNodeId",id);
            edge.put("properties",Map.of("source","registeredWarehouseModel", "kind",
                    dimensionIds.contains(parent) ? "DIMENSION_REFERENCE" : "PROCESSING"));
            edges.add(edge);
        }
        path.remove(id);
    }
}
