package tech.qiantong.qdata.module.dpp.service.etl.task;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLUseStatement;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tech.qiantong.qdata.common.database.core.HiveTableIdentity;
import java.util.*;

/** Ordinary SQL and integration sinks cannot mutate retained managed versions. */
@Component
@RequiredArgsConstructor
public class DppHiveWritePolicy {
    private final JdbcTemplate jdbc;
    public void table(String name,String defaultDatabase) {
        var table=HiveTableIdentity.resolve(name,defaultDatabase);
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_batch_step b JOIN dp_model m ON m.id=b.model_id WHERE m.database_name=? AND m.model_name=?",Long.class,table.databaseName(),table.tableName());
        if(count!=null && count>0)throw new IllegalArgumentException("固定批次数据不能原地修改，请使用新的数据版本："+table.canonical());
    }
    private List<Map<String,Object>> namespace(String database,Long datasourceId) {
        String sql="SELECT n.layer_code,n.space_id,n.engine_id FROM dp_warehouse_namespace n JOIN dp_warehouse_engine e ON e.id=n.engine_id WHERE n.database_name=?";
        return datasourceId==null?jdbc.queryForList(sql,database):jdbc.queryForList(sql+" AND e.datasource_id=?",database,datasourceId);
    }
    public void sql(String text,String defaultDatabase) {
        sql(text,defaultDatabase,null);
    }
    public void sql(String text,String defaultDatabase,Long datasourceId) {
        for(var statement:SQLUtils.parseStatements(text,DbType.hive)) {
            if(statement instanceof SQLSelectStatement)continue;
            if(statement instanceof SQLUseStatement)throw new IllegalArgumentException("Hive 开发SQL请使用完整库表名，不能切换默认数据库");
            var visitor=SQLUtils.createSchemaStatVisitor(DbType.hive);statement.accept(visitor);
            var changes=visitor.getTables().entrySet().stream().filter(e->{var s=e.getValue();return s.getInsertCount()+s.getUpdateCount()+s.getDeleteCount()+s.getDropCount()+s.getAlterCount()+s.getCreateCount()+s.getMergeCount()+s.getAddCount()+s.getAddPartitionCount()+s.getCreateIndexCount()+s.getDropIndexCount()>0;}).toList();
            var targets=changes.isEmpty()?visitor.getTables().keySet().stream().map(t->t.getName()).toList():changes.stream().map(t->t.getKey().getName()).toList();
            for(String target:targets) {
                table(target,defaultDatabase);
                var resolved=HiveTableIdentity.resolve(target,defaultDatabase);
                if(!namespace(resolved.databaseName(),datasourceId).isEmpty())
                    throw new IllegalArgumentException("已登记的Hive五层库只能通过数仓模型和受管批次写入："+resolved.canonical());
            }
        }
    }
    public void integration(JSONObject parameters) {
        var writer=parameters.getJSONObject("writerDatasource");
        if(writer==null || !"HIVE".equalsIgnoreCase(writer.getString("datasourceType")))return;
        var config=writer.getJSONObject("datasourceConfig");String database=config==null?writer.getString("dbname"):config.getString("dbname");
        Long datasourceId=writer.getLong("datasourceId");if(datasourceId==null)datasourceId=writer.getLong("id");
        String target=parameters.getString("target_table_name");
        if(target!=null && !target.isBlank()) {
            table(target,database);
            var resolved=HiveTableIdentity.resolve(target,database);
            var namespaces=namespace(resolved.databaseName(),datasourceId);
            if(!namespaces.isEmpty()) {
                if(datasourceId==null || namespaces.size()!=1 || !"ODS".equals(namespaces.get(0).get("layer_code")))
                    throw new IllegalArgumentException("已登记的Hive五层库仅允许向ODS原始层采集");
                Long declaredSource=parameters.getLong("target_datasource_id");
                if(declaredSource!=null && !datasourceId.equals(declaredSource))
                    throw new IllegalArgumentException("采集目标与已登记Hive连接不一致");
                Long assetId=writer.getLong("target_asset_id");if(assetId==null)assetId=parameters.getLong("target_asset_id");
                if(assetId==null || !Integer.valueOf(2).equals(parameters.getInteger("writeModeType")))
                    throw new IllegalArgumentException("ODS采集必须选择已登记资产并使用追加模式");
                var scope=namespaces.get(0);
                Long approved=jdbc.queryForObject("SELECT COUNT(*) FROM da_asset a JOIN dp_warehouse_materialization z ON z.dataset_id=a.dataset_id AND z.database_name=a.database_name AND z.table_name=a.table_name JOIN dp_model m ON m.id=z.model_id WHERE a.id=? AND a.datasource_id=? AND a.database_name=? AND a.table_name=? AND z.engine_id=? AND m.datasource_id=? AND JSON_UNQUOTE(JSON_EXTRACT(m.warehouse_config,'$.layer'))='ODS' AND CAST(JSON_UNQUOTE(JSON_EXTRACT(m.warehouse_config,'$.spaceId')) AS UNSIGNED)=? AND a.del_flag='0'",Long.class,assetId,datasourceId,resolved.databaseName(),resolved.tableName(),scope.get("engine_id"),datasourceId,scope.get("space_id"));
                if(approved==null || approved!=1)throw new IllegalArgumentException("ODS采集目标必须是该空间已登记的模型资产："+resolved.canonical());
            }
        }
        for(String key:List.of("preSql","postSql")) {
            Object value=parameters.get(key);if(value==null)continue;
            var scripts=value instanceof Collection<?>?(Collection<?>)value:List.of(value);
            for(Object script:scripts)if(script!=null && !script.toString().isBlank())sql(script.toString().replaceAll("(?i),\\s*(?=SET\\s)",";"),database,datasourceId);
        }
    }
}
