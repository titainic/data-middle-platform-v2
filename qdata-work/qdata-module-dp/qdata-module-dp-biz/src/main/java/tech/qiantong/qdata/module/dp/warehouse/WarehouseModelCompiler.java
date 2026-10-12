package tech.qiantong.qdata.module.dp.warehouse;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import com.alibaba.fastjson2.JSON;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import static tech.qiantong.qdata.module.dp.warehouse.WarehouseSql.require;

/** Pure compilation: fixed logical definitions to fresh physical version targets. */
public final class WarehouseModelCompiler {
    private WarehouseModelCompiler() { }
    public record Step(long templateModelId,int templateRevision,String sourceTable,String targetTable,
                       WarehouseDefinition definition,List<Long> dependencies) { }

    public static List<Step> compile(List<WarehouseDefinition> templates,List<Long> leaves,String batchId) {
        String suffix=UUID.fromString(batchId).toString().replace("-","");
        Map<Long,WarehouseDefinition> models=new LinkedHashMap<>();
        Set<Long> managedIds=new HashSet<>();
        for(var input:templates) {
            require(input.getId()!=null && input.getSpaceId()!=null && input.getDatasetId()!=null,"持续批次只支持已登记空间和数据集的模型");
            require(models.put(input.getId(),JSON.parseObject(JSON.toJSONString(input),WarehouseDefinition.class))==null,"模板模型重复");
            if(input.getManagedBatchId()!=null)managedIds.add(input.getId());
        }
        require(leaves!=null && !leaves.isEmpty(),"请选择需要交付的下游模型");
        LinkedHashMap<Long,WarehouseDefinition> ordered=new LinkedHashMap<>();
        for(Long leaf:leaves)collect(leaf,models,ordered,new LinkedHashSet<>());
        require(ordered.keySet().stream().noneMatch(managedIds::contains),"请选择可编辑模板；固定批次版本应通过原批次重试");
        require(ordered.size()<=1000,"单批模型数量超过当前运行上限");
        var first=ordered.values().iterator().next();
        Set<Long> datasets=new HashSet<>();
        Map<String,String> mapping=new LinkedHashMap<>();
        for(var model:ordered.values()) {
            require(model.getManagedBatchId()==null,"请选择可编辑模板；固定批次版本应通过原批次重试");
            require(datasets.add(model.getDatasetId()),"同一批次不能混用同一数据集的多个模板物理版本");
            require(Objects.equals(first.getSpaceId(),model.getSpaceId()) && Objects.equals(first.getDatasourceId(),model.getDatasourceId()),"批次模型必须属于同一空间和执行连接");
            String target=versionTable(model.getTableName(),suffix);
            mapping.put(WarehouseSql.canonical(model.getTableName(),model.getDatabaseName()),WarehouseSql.canonical(target,model.getDatabaseName()));
        }
        List<Step> steps=new ArrayList<>();
        for(var template:ordered.values()) {
            var model=JSON.parseObject(JSON.toJSONString(template),WarehouseDefinition.class);
            long sourceId=model.getId();int revision=model.getRevision();String source=WarehouseSql.canonical(model.getTableName(),model.getDatabaseName());String target=mapping.get(source);
            Set<Long> depends=new LinkedHashSet<>(model.getUpstreamIds());model.getDimensions().forEach(r->depends.add(r.getModelId()));
            if(!"ODS".equals(model.getLayer())) {
                Set<String> declared=new LinkedHashSet<>();model.getUpstreamIds().forEach(id->{var up=models.get(id);declared.add(WarehouseSql.canonical(up.getTableName(),up.getDatabaseName()));});
                require(WarehouseSql.readTables(model.getSelectSql(),model.getDatabaseName()).equals(declared),"模板实际来源与登记上游不一致");
                model.setSelectSql(rewriteTables(model.getSelectSql(),mapping));
            } else {
                require(model.getUpstreamIds().isEmpty(),"ODS 不能包含业务加工上游");
                model.setRawTable("");model.setSelectSql("");
            }
            model.setId(null);model.setRevision(1);model.setTableName(target.substring(target.indexOf('.')+1));
            // Model references are rebound to new IDs only after parent versions are registered.
            steps.add(new Step(sourceId,revision,source,target,model,List.copyOf(depends)));
        }
        return steps;
    }

    private static void collect(Long id,Map<Long,WarehouseDefinition> models,Map<Long,WarehouseDefinition> ordered,Set<Long> path) {
        if(id==null || !models.containsKey(id))throw new IllegalArgumentException("缺少上游或维度模型快照，引用路径："+dependencyPath(path,id,models));
        if(ordered.containsKey(id))return;
        if(!path.add(id))throw new IllegalArgumentException("模型快照包含循环依赖，引用路径："+dependencyPath(path,id,models));
        var model=models.get(id);
        for(Long parent:model.getUpstreamIds())collect(parent,models,ordered,path);
        for(var relation:model.getDimensions())collect(relation.getModelId(),models,ordered,path);
        path.remove(id);ordered.put(id,model);
    }

    private static String dependencyPath(Set<Long> path, Long end, Map<Long,WarehouseDefinition> models) {
        List<Long> ids=new ArrayList<>(path);ids.add(end);
        return ids.stream().map(id->{var model=models.get(id);return model==null?"missing["+id+"]":
                Objects.toString(model.getDatabaseName(),"?")+"."+Objects.toString(model.getTableName(),"?")+"["+id+"]";})
                .collect(java.util.stream.Collectors.joining(" -> "));
    }

    static String versionTable(String table,String suffix) {
        WarehouseSql.id(table);String prefix=table;
        if(prefix.length()>94) {
            try {prefix=prefix.substring(0,80)+"_"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(table.getBytes(StandardCharsets.UTF_8))).substring(0,12);}
            catch(Exception e){throw new IllegalStateException(e);}
        }
        return prefix+"__"+suffix;
    }

    static String rewriteTables(String sql,Map<String,String> mapping) {
        var statement=SQLUtils.parseSingleStatement(sql,DbType.hive);
        require(statement instanceof SQLSelectStatement,"运行快照仅接受 SELECT/WITH 加工语句");
        statement.accept(new SQLASTVisitorAdapter(){
            public boolean visit(SQLExprTableSource source) {
                String key=source.getExpr().toString().replace("`","").toLowerCase(Locale.ROOT);
                // Registered warehouse SQL is fully qualified; CTE names and literals remain intact.
                String target=mapping.get(key);
                if(target!=null){String[] parts=target.split("\\.");source.setExpr(SQLUtils.toSQLExpr(WarehouseSql.qualified(parts[0],parts[1]),DbType.hive));}
                return true;
            }
        });
        return SQLUtils.toSQLString(statement,DbType.hive);
    }

    static void applyWindow(WarehouseDefinition definition,LocalDate from,LocalDate through) {
        require("DWD".equals(definition.getLayer()) && definition.getColumns().stream().anyMatch(c->"event_date".equals(c.getName()) && Set.of("DATE","STRING").contains(WarehouseSql.type(c.getType()))),"窗口加工需要 DWD 声明 event_date 标准日期字段");
        require(from!=null && through!=null && !from.isAfter(through),"业务窗口无效");
        var statement=(SQLSelectStatement)SQLUtils.parseSingleStatement(definition.getSelectSql(),DbType.hive);
        var query=statement.getSelect();var with=query.getWithSubQuery();query.setWithSubQuery(null);
        definition.setSelectSql((with==null?"":SQLUtils.toSQLString(with,DbType.hive)+"\n")+"SELECT * FROM ("+SQLUtils.toSQLString(query,DbType.hive)+") warehouse_window WHERE CAST(`event_date` AS DATE) >= CAST('"+from+"' AS DATE) AND CAST(`event_date` AS DATE) <= CAST('"+through+"' AS DATE)");
    }

    static void deduplicateInputs(WarehouseDefinition definition,List<WarehouseDefinition> sources) {
        require("DWD".equals(definition.getLayer()) && sources.size()==1,"当前IoT增量规则需要DWD对应一个原始数据集");
        var source=sources.get(0);var names=source.getColumns().stream().map(WarehouseDefinition.Column::getName).toList();
        var keys=definition.getColumns().stream().filter(WarehouseDefinition.Column::isKey).map(WarehouseDefinition.Column::getName).toList();
        require(!keys.isEmpty() && names.containsAll(keys) && names.containsAll(List.of("updated_at","created_at")),"原始数据需包含业务键、updated_at和created_at，才能固定修正记录的优先级");
        String table=WarehouseSql.canonical(source.getTableName(),source.getDatabaseName());
        var statement=(SQLSelectStatement)SQLUtils.parseSingleStatement(definition.getSelectSql(),DbType.hive);
        String alias="qdata_latest_input";
        var query=statement.getSelect();var with=query.getWithSubQuery();
        if(with!=null)require(with.getEntries().stream().noneMatch(e->alias.equalsIgnoreCase(e.getAlias())),"加工SQL占用了批次保留的CTE名称");
        statement.accept(new SQLASTVisitorAdapter(){public boolean visit(SQLExprTableSource node){
            if(table.equalsIgnoreCase(node.getExpr().toString().replace("`","")))node.setExpr(SQLUtils.toSQLExpr(alias,DbType.hive));return true;
        }});
        String fields=names.stream().map(WarehouseSql::id).collect(java.util.stream.Collectors.joining(","));
        List<String> priority=new ArrayList<>(List.of("updated_at","created_at"));names.stream().filter(n->!priority.contains(n)).sorted().forEach(priority::add);
        String order=priority.stream().map(n->WarehouseSql.id(n)+" DESC").collect(java.util.stream.Collectors.joining(","));
        String ranked="SELECT "+fields+" FROM (SELECT "+fields+",ROW_NUMBER() OVER (PARTITION BY "+keys.stream().map(WarehouseSql::id).collect(java.util.stream.Collectors.joining(","))+" ORDER BY "+order+") AS qdata_rank FROM "+WarehouseSql.qualified(source.getDatabaseName(),source.getTableName())+") qdata_ranked WHERE qdata_rank=1";
        var input=((SQLSelectStatement)SQLUtils.parseSingleStatement(ranked,DbType.hive)).getSelect();
        if(with==null){with=new com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause();query.setWithSubQuery(with);}
        var entry=new com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause.Entry(alias,input);entry.setParent(with);with.getEntries().add(0,entry);
        definition.setSelectSql(SQLUtils.toSQLString(statement,DbType.hive));
        definition.setGrain("按业务键保留一条观测；updated_at、created_at及原始字段降序确定修正优先级（批次规则 v1）");
    }
}
