package tech.qiantong.qdata.module.dp.warehouse;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.module.ds.api.api.WarehouseApiRegistrar;
import tech.qiantong.qdata.redis.service.IRedisService;
import tech.qiantong.qdata.redis.service.OwnedRedisLease;
import java.sql.*;
import java.util.*;
import static tech.qiantong.qdata.module.dp.warehouse.WarehouseSql.require;

/** Append immutable release rows to Doris and switch one bundle manifest only after complete verification. */
@Service
@RequiredArgsConstructor
public class WarehouseServingService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final WarehouseSpaceService spaces;
    private final WarehouseBatchRegistry batches;
    private final WarehouseQualityService quality;
    private final WarehouseService models;
    private final IDaDatasourceApiService sources;
    private final DataSourceFactory factory;
    private final WarehouseApiRegistrar apis;
    private final IRedisService redis;

    public Map<String,Object> configure(long spaceId,long sourceId,String database,int replicas) {
        spaces.authorize(spaceId);WarehouseSql.id(database);
        require(replicas>=1 && replicas<=3,"副本数需为1至3");
        require(spaces.administrator() || jdbc.queryForObject("SELECT COUNT(*) FROM da_datasource_project_rel WHERE datasource_id=? AND project_id=? AND valid_flag='1'",Long.class,sourceId,spaces.getInternal(spaceId).getProjectId())>0,"服务连接未授权给当前项目");
        var source=sources.getDatasourceById(sourceId);require(source!=null && "Doris".equalsIgnoreCase(source.getDatasourceType()),"请选择Doris服务连接");
        var previous=jdbc.queryForList("SELECT * FROM dp_warehouse_serving_target WHERE space_id=?",spaceId);
        if(!previous.isEmpty()) {
            require(Objects.equals(((Number)previous.get(0).get("datasource_id")).longValue(),sourceId) && database.equals(previous.get(0).get("database_name")) && ((Number)previous.get(0).get("replicas")).intValue()==replicas,"已登记的服务目标不可原地切换");return target(spaceId);
        }
        var query=factory.createDbQuery(property(sourceId));
        try(Connection connection=query.getConnection();Statement statement=connection.createStatement()) {
            statement.setQueryTimeout(30);statement.execute("CREATE DATABASE IF NOT EXISTS "+WarehouseSql.id(database));
        } catch(Exception failure) {throw new IllegalArgumentException("Doris服务库预检或创建失败",failure);}
        finally {query.close();}
        jdbc.update("INSERT INTO dp_warehouse_serving_target(space_id,datasource_id,database_name,connection_fingerprint,replicas) VALUES(?,?,?,?,?)",spaceId,sourceId,database,WarehouseSpaceService.connectionFingerprint(property(sourceId)),replicas);
        return target(spaceId);
    }
    DbQueryProperty property(long sourceId) {return property(sourceId,180000);}
    DbQueryProperty property(long sourceId,int socketTimeout) {
        var source=sources.getDatasourceById(sourceId);require(source!=null,"服务连接不存在");
        return new DbQueryProperty(source.getDatasourceType(),source.getIp(),source.getPort(),source.getDatasourceConfig()) {
            @Override public String trainToJdbcUrl(){return super.trainToJdbcUrl()+"&connectTimeout=5000&socketTimeout="+socketTimeout+"&autoReconnect=false";}
        };
    }
    Map<String,Object> target(long spaceId){
        var row=jdbc.queryForMap("SELECT * FROM dp_warehouse_serving_target WHERE space_id=?",spaceId);
        require(WarehouseSpaceService.connectionFingerprint(property(((Number)row.get("datasource_id")).longValue())).equals(row.get("connection_fingerprint")),"Doris连接地址已变化，请恢复已登记的执行环境");return row;
    }
    public Map<String,Object> overview(long spaceId) {
        spaces.authorize(spaceId);var configured=jdbc.queryForList("SELECT * FROM dp_warehouse_serving_target WHERE space_id=?",spaceId);
        return Map.of("target",configured,"releases",releases(spaceId),"heads",jdbc.queryForList("SELECT * FROM dp_warehouse_serving_head WHERE space_id=?",spaceId),"sources",jdbc.queryForList("SELECT DISTINCT d.id,d.datasource_name FROM da_datasource d JOIN da_datasource_project_rel r ON r.datasource_id=d.id WHERE d.datasource_type='Doris' AND d.del_flag='0' AND r.project_id=? AND r.valid_flag='1'",spaces.getInternal(spaceId).getProjectId()));
    }
    public Map<String,Object> scope(long spaceId,long clientId,List<String> locations,List<String> columns,List<String> maskedColumns) {
        spaces.authorize(spaceId);require(locations!=null && locations.size()<=100 && locations.stream().allMatch(value->value!=null && !value.isBlank() && value.length()<=128),"地点范围无效");
        require(jdbc.queryForObject("SELECT COUNT(*) FROM att_client WHERE id=? AND del_flag='0'",Long.class,clientId)==1,"应用不存在");
        var normalized=locations.stream().distinct().sorted().toList();
        require(columns!=null && maskedColumns!=null && columns.size()<=500 && maskedColumns.size()<=500,"字段范围无效");
        columns.forEach(WarehouseSql::id);maskedColumns.forEach(WarehouseSql::id);
        Set<String> available=new HashSet<>();for(String schema:jdbc.queryForList("SELECT schema_json FROM dp_warehouse_serving_product WHERE space_id=?",String.class,spaceId))JSON.parseArray(schema,WarehouseDefinition.Column.class).forEach(column->available.add(column.getName()));
        require(available.containsAll(columns) && available.containsAll(maskedColumns),"字段范围包含未登记的服务字段");
        jdbc.update("INSERT INTO dp_warehouse_serving_scope(client_id,space_id,locations_json,columns_json,masked_columns_json) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE locations_json=VALUES(locations_json),columns_json=VALUES(columns_json),masked_columns_json=VALUES(masked_columns_json)",clientId,spaceId,JSON.toJSONString(normalized),JSON.toJSONString(columns.stream().distinct().sorted().toList()),JSON.toJSONString(maskedColumns.stream().distinct().sorted().toList()));
        return Map.of("clientId",clientId,"spaceId",spaceId,"locations",normalized,"columns",columns,"maskedColumns",maskedColumns);
    }
    public List<Map<String,Object>> releases(long spaceId) {spaces.authorize(spaceId);return jdbc.queryForList("SELECT * FROM dp_warehouse_serving_release WHERE space_id=? ORDER BY created_at DESC",spaceId);}
    public Map<String,Object> get(String id) {
        var row=jdbc.queryForMap("SELECT * FROM dp_warehouse_serving_release WHERE id=?",id);spaces.authorize(((Number)row.get("space_id")).longValue());
        var result=new LinkedHashMap<>(row);result.put("products",jdbc.queryForList("SELECT r.*,p.api_id,p.table_name FROM dp_warehouse_serving_release_product r JOIN dp_warehouse_serving_release s ON s.id=r.release_id JOIN dp_warehouse_serving_product p ON p.space_id=s.space_id AND p.dataset_id=r.dataset_id AND p.contract_version=r.contract_version WHERE r.release_id=? ORDER BY r.dataset_id",id));return result;
    }
    public Map<String,Object> synchronize(String batchId,String assessmentId,int version,boolean newAttempt) {
        require(version>0 && version<=100,"服务契约版本无效");var batch=batches.get(batchId);long spaceId=((Number)batch.get("space_id")).longValue();
        require("SUCCESS".equals(batch.get("status")),"服务同步需要整批加工成功，不允许交付部分成功产品");
        var assessment=quality.get(assessmentId);require("PASSED".equals(assessment.get("status")) && batchId.equals(assessment.get("batch_id")),"服务同步需要本批已通过的质量评估");
        var requiredModels=jdbc.queryForList("SELECT s.model_id FROM dp_warehouse_batch_step s JOIN dp_model m ON m.id=s.model_id WHERE s.batch_id=? AND JSON_UNQUOTE(JSON_EXTRACT(m.warehouse_config,'$.layer'))='DWD'",Long.class,batchId);
        var assessedModels=jdbc.queryForList("SELECT model_id FROM dp_warehouse_quality_assessment_task WHERE assessment_id=? AND status='PASSED'",Long.class,assessmentId);
        requireCoverage(requiredModels,assessedModels);
        var selected=completedProducts(batchId);
        var old=jdbc.queryForList("SELECT id FROM dp_warehouse_serving_release WHERE batch_id=? AND assessment_id=? AND contract_version=? AND status IN ('VERIFIED','PUBLISHED') ORDER BY created_at DESC LIMIT 1",batchId,assessmentId,version);
        if(!old.isEmpty() && !newAttempt)return get(String.valueOf(old.get(0).get("id")));
        try(var lease=OwnedRedisLease.acquire(redis,"warehouse:serving:"+spaceId+":"+version,180)) {
            if(!newAttempt) {
                var completed=jdbc.queryForList("SELECT id FROM dp_warehouse_serving_release WHERE batch_id=? AND assessment_id=? AND contract_version=? AND status IN ('VERIFIED','PUBLISHED') ORDER BY created_at DESC LIMIT 1",String.class,batchId,assessmentId,version);
                if(!completed.isEmpty())return get(completed.get(0));
            }
            var target=target(spaceId);String database=String.valueOf(target.get("database_name"));long sourceId=((Number)target.get("datasource_id")).longValue();
            var definitions=selected.stream().map(row->models.getInternal(((Number)row.get("model_id")).longValue())).toList();
            var existing=jdbc.queryForList("SELECT dataset_id FROM dp_warehouse_serving_product WHERE space_id=? AND contract_version=?",Long.class,spaceId,version);
            require(existing.isEmpty() || new HashSet<>(existing).equals(new HashSet<>(definitions.stream().map(WarehouseDefinition::getDatasetId).toList())),"必须同时交付当前服务契约的全部产品");
            String release=UUID.randomUUID().toString();jdbc.update("INSERT INTO dp_warehouse_serving_release(id,space_id,batch_id,assessment_id,contract_version,status,creator_id,message) VALUES(?,?,?,?,?,'SYNCING',?,?)",release,spaceId,batchId,assessmentId,version,SecurityUtils.getUserId(),"正在核验Hive并同步Doris版本数据");
            try {
                for(var model:definitions) {
                    lease.requireOwner();var columns=WarehouseSql.ordered(model);String schema=JSON.toJSONString(columns);
                    var registered=jdbc.queryForList("SELECT * FROM dp_warehouse_serving_product WHERE space_id=? AND dataset_id=? AND contract_version=?",spaceId,model.getDatasetId(),version);
                    String table;
                    if(registered.isEmpty()) {
                        table="iot_"+model.getDatasetId()+"_v"+version+"_"+UUID.randomUUID().toString().replace("-","").substring(0,8);
                        jdbc.update("INSERT INTO dp_warehouse_serving_product(space_id,dataset_id,contract_version,table_name,schema_json) VALUES(?,?,?,?,?)",spaceId,model.getDatasetId(),version,table,schema);
                    } else {
                        table=String.valueOf(registered.get(0).get("table_name"));
                        var prior=JSON.parseArray(String.valueOf(registered.get(0).get("schema_json")),WarehouseDefinition.Column.class);
                        require(signature(prior).equals(signature(columns)),"字段契约已变化，请使用新的服务契约版本");
                    }
                    jdbc.update("INSERT INTO dp_warehouse_serving_release_product(release_id,dataset_id,contract_version,model_id,status) VALUES(?,?,?,?,'SYNCING')",release,model.getDatasetId(),version,model.getId());
                    var rows=readHive(model,batchId);rows.sort(Comparator.comparing(row->ServingRowCodec.row(row,columns)));String expected=ServingRowCodec.fingerprint(rows,columns);
                    ensureTable(sourceId,database,table,columns,((Number)target.get("replicas")).intValue());
                    append(sourceId,database,table,release,rows,columns,lease);
                    var actual=readDoris(sourceId,database,table,release,columns);
                    require(expected.equals(ServingRowCodec.fingerprint(actual,columns)) && rows.size()==actual.size(),"Doris副本与Hive数据不一致");
                    require(expected.equals(ServingRowCodec.fingerprint(readHive(model,batchId),columns)),"同步期间Hive输入发生变化");
                    lease.requireOwner();jdbc.update("UPDATE dp_warehouse_serving_release_product SET status='VERIFIED',row_count=?,fingerprint=? WHERE release_id=? AND dataset_id=? AND contract_version=?",rows.size(),expected,release,model.getDatasetId(),version);
                    Long api=jdbc.queryForObject("SELECT api_id FROM dp_warehouse_serving_product WHERE space_id=? AND dataset_id=? AND contract_version=?",Long.class,spaceId,model.getDatasetId(),version);
                    if(api==null) {
                        var fields=columns.stream().map(column->{Map<String,Object> item=new LinkedHashMap<>();item.put("name",column.getName());item.put("type",column.getType());item.put("comment",column.getComment()==null?column.getName():column.getComment());return item;}).toList();
                        String code=jdbc.queryForObject("SELECT code FROM dp_warehouse_dataset WHERE id=?",String.class,model.getDatasetId());
                        api=apis.register("IoT / "+code+" / v"+version,"iot/"+spaceId+"/"+code,version,sourceId,database,table,fields);
                        jdbc.update("UPDATE dp_warehouse_serving_product SET api_id=? WHERE space_id=? AND dataset_id=? AND contract_version=?",api,spaceId,model.getDatasetId(),version);
                    }
                }
                lease.requireOwner();jdbc.update("UPDATE dp_warehouse_serving_release SET status='VERIFIED',finished_at=NOW(6),message=? WHERE id=?","全部产品副本已对账，等待发布清单切换",release);
            } catch(Exception failure) {
                try {
                    transaction.executeWithoutResult(status->{
                        jdbc.update("UPDATE dp_warehouse_serving_release_product SET status='FAILED' WHERE release_id=? AND status='SYNCING'",release);
                        jdbc.update("UPDATE dp_warehouse_serving_release SET status='FAILED',finished_at=NOW(6),message=? WHERE id=?",failure.getMessage(),release);
                    });
                } catch(Exception recordingFailure) {failure.addSuppressed(recordingFailure);}
                throw new IllegalArgumentException("服务同步失败；已有发布保持，失败副本保留",failure);
            }
            return get(release);
        }
    }
    public Map<String,Object> publish(String id) {
        var release=get(id);long spaceId=((Number)release.get("space_id")).longValue();int version=((Number)release.get("contract_version")).intValue();
        require(Set.of("VERIFIED","PUBLISHED").contains(String.valueOf(release.get("status"))),"仅对账完成的版本可以发布或回退");
        String batchId=String.valueOf(release.get("batch_id"));var batch=batches.get(batchId);
        require("SUCCESS".equals(batch.get("status")) && ((Number)batch.get("space_id")).longValue()==spaceId,"发布来源批次未全部成功或不属于当前空间");
        var assessment=quality.get(String.valueOf(release.get("assessment_id")));
        require("PASSED".equals(assessment.get("status")) && batchId.equals(assessment.get("batch_id")),"质量门禁未通过或不属于发布来源批次");
        var products=jdbc.queryForList("SELECT r.*,p.api_id,p.table_name,p.schema_json FROM dp_warehouse_serving_release_product r JOIN dp_warehouse_serving_product p ON p.space_id=? AND p.dataset_id=r.dataset_id AND p.contract_version=r.contract_version WHERE r.release_id=?",spaceId,id);
        requireCompleteProducts(completedProducts(batchId),products,jdbc.queryForList("SELECT dataset_id FROM dp_warehouse_serving_product WHERE space_id=? AND contract_version=?",Long.class,spaceId,version),version);
        require(!products.isEmpty() && products.stream().allMatch(product->"VERIFIED".equals(product.get("status")) && product.get("api_id")!=null),"产品尚未全部就绪");
        var target=target(spaceId);
        for(var product:products) {
            var columns=JSON.parseArray(String.valueOf(product.get("schema_json")),WarehouseDefinition.Column.class);
            try {
                var rows=readDoris(((Number)target.get("datasource_id")).longValue(),String.valueOf(target.get("database_name")),String.valueOf(product.get("table_name")),id,columns);
                require(rows.size()==((Number)product.get("row_count")).longValue() && ServingRowCodec.fingerprint(rows,columns).equals(product.get("fingerprint")),"发布前副本对账失败，现有发布保持");
            }catch(Exception failure){throw new IllegalArgumentException("发布前Doris复核失败，现有发布保持",failure);}
        }
        for(var product:products)apis.activate(((Number)product.get("api_id")).longValue());
        transaction.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT id FROM dp_warehouse_space WHERE id=? FOR UPDATE",Long.class,spaceId);
            var before=jdbc.queryForList("SELECT release_id FROM dp_warehouse_serving_head WHERE space_id=? AND contract_version=?",String.class,spaceId,version);
            jdbc.update("INSERT INTO dp_warehouse_serving_head(space_id,contract_version,release_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE release_id=VALUES(release_id),updated_at=NOW(6)",spaceId,version,id);
            jdbc.update("UPDATE dp_warehouse_serving_release SET status='PUBLISHED',ever_published=TRUE,message=? WHERE id=?","全部产品已统一发布，可通过应用授权消费",id);
            jdbc.update("INSERT INTO dp_warehouse_serving_audit(space_id,release_id,previous_release_id,actor_id) VALUES(?,?,?,?)",spaceId,id,before.isEmpty()?null:before.get(0),SecurityUtils.getUserId());
        });return get(id);
    }
    private List<Map<String,Object>> completedProducts(String batchId) {
        var selected=jdbc.queryForList("SELECT dataset_id,model_id,status FROM dp_warehouse_batch_step WHERE batch_id=? AND JSON_UNQUOTE(JSON_EXTRACT(definition_json,'$.layer'))='ADS' ORDER BY dataset_id",batchId);
        require(!selected.isEmpty() && selected.stream().allMatch(row->"SUCCESS".equals(row.get("status"))),"批次ADS产品尚未全部成功");
        return selected;
    }
    static void requireCompleteProducts(List<Map<String,Object>> expected,List<Map<String,Object>> actual,List<Long> registered,int version) {
        Map<Long,Long> models=new HashMap<>();
        for(var row:expected) require(models.put(((Number)row.get("dataset_id")).longValue(),((Number)row.get("model_id")).longValue())==null,"批次包含重复ADS数据集");
        require(!models.isEmpty() && models.keySet().equals(new HashSet<>(registered)) && registered.size()==models.size(),"服务契约与批次产品集合不完整或不一致");
        require(actual.size()==models.size(),"发布清单未包含当前契约的全部产品");
        Set<Long> seen=new HashSet<>();
        for(var row:actual) {
            long dataset=((Number)row.get("dataset_id")).longValue();
            require(seen.add(dataset) && Objects.equals(models.get(dataset),((Number)row.get("model_id")).longValue()) && ((Number)row.get("contract_version")).intValue()==version,"发布清单产品、模型版本或契约版本不一致");
        }
    }
    static void requireCoverage(List<Long> required,List<Long> assessed){require(!required.isEmpty() && new HashSet<>(assessed).containsAll(required),"质量评估必须覆盖本批所有DWD明细数据集");}
    public Map<String,Object> scope(long spaceId,long clientId) {
        spaces.authorize(spaceId);var rows=jdbc.queryForList("SELECT * FROM dp_warehouse_serving_scope WHERE space_id=? AND client_id=?",spaceId,clientId);
        if(rows.isEmpty())return Map.of("locations",List.of(),"columns",List.of(),"maskedColumns",List.of());
        var row=rows.get(0);return Map.of("locations",JSON.parseArray(String.valueOf(row.get("locations_json")),String.class),"columns",JSON.parseArray(String.valueOf(row.get("columns_json")),String.class),"maskedColumns",JSON.parseArray(String.valueOf(row.get("masked_columns_json")),String.class));
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)
    public void recoverInterruptedSyncs() {
        for(var row:jdbc.queryForList("SELECT id,space_id,contract_version FROM dp_warehouse_serving_release WHERE status='SYNCING' AND created_at<DATE_SUB(NOW(),INTERVAL 180 SECOND)")) {
            if(redis.get("warehouse:serving:"+row.get("space_id")+":"+row.get("contract_version"))!=null)continue;
            transaction.executeWithoutResult(status->{
                int changed=jdbc.update("UPDATE dp_warehouse_serving_release SET status='FAILED',finished_at=NOW(6),message=? WHERE id=? AND status='SYNCING'","同步执行已中断，请新建尝试；历史发布和已写入副本保留",row.get("id"));
                if(changed==1)jdbc.update("UPDATE dp_warehouse_serving_release_product SET status='FAILED' WHERE release_id=? AND status='SYNCING'",row.get("id"));
            });
        }
    }
    private List<List<String>> readHive(WarehouseDefinition model,String batchId) throws Exception {
        var query=factory.createDbQuery(models.property(model));var columns=WarehouseSql.ordered(model);
        try(Connection connection=query.getConnection()) {
            var observed=WarehouseDataFingerprint.read(connection,List.of(WarehouseSql.canonical(model.getTableName(),model.getDatabaseName())),columns,null,null,1_000_000);
            String expected=jdbc.queryForObject("SELECT output_fingerprint FROM dp_warehouse_batch_step WHERE batch_id=? AND model_id=? AND status='SUCCESS'",String.class,batchId,model.getId());
            require(observed.sha256().equals(expected),"ADS版本内容与批次完成记录不一致");
            return read(connection,"SELECT "+fields(columns)+" FROM "+WarehouseSql.qualified(model.getDatabaseName(),model.getTableName()),columns.size());
        } finally {query.close();}
    }
    private void ensureTable(long source,String database,String table,List<WarehouseDefinition.Column> columns,int replicas) throws Exception {
        var query=factory.createDbQuery(property(source));
        try(Connection connection=query.getConnection();Statement statement=connection.createStatement()) {
            String ddl="CREATE TABLE IF NOT EXISTS "+WarehouseSql.qualified(database,table)+" (`_qdata_release_id` VARCHAR(36) NOT NULL,`_qdata_row_number` BIGINT NOT NULL,"+String.join(",",columns.stream().map(column->WarehouseSql.id(column.getName())+" "+ServingRowCodec.type(column.getType())+" NULL").toList())+") DUPLICATE KEY(`_qdata_release_id`,`_qdata_row_number`) DISTRIBUTED BY HASH(`_qdata_release_id`) BUCKETS 1 PROPERTIES ('replication_num'='"+replicas+"')";
            statement.setQueryTimeout(60);
            statement.execute(ddl);
            try(var result=statement.executeQuery("SELECT "+fields(columns)+" FROM "+WarehouseSql.qualified(database,table)+" LIMIT 0")){DorisServingSchema.verify(result.getMetaData(),columns);}
        } finally {query.close();}
    }
    private void append(long source,String database,String table,String release,List<List<String>> rows,List<WarehouseDefinition.Column> columns,OwnedRedisLease lease) throws Exception {
        var query=factory.createDbQuery(property(source));
        try(Connection connection=query.getConnection();PreparedStatement statement=connection.prepareStatement("INSERT INTO "+WarehouseSql.qualified(database,table)+" (`_qdata_release_id`,`_qdata_row_number`,"+fields(columns)+") VALUES("+String.join(",",Collections.nCopies(columns.size()+2,"?"))+")")) {
            int ordinal=0,pending=0;statement.setQueryTimeout(120);
            for(var row:rows) {
                statement.setString(1,release);statement.setLong(2,++ordinal);
                for(int i=0;i<columns.size();i++){String value=row.get(i);if(value==null)statement.setNull(i+3,Types.NULL);else statement.setObject(i+3,ServingRowCodec.response(value,columns.get(i).getType()));}
                statement.addBatch();pending++;
                if(pending==200){lease.requireOwner();statement.executeBatch();pending=0;}
            }
            if(pending>0){lease.requireOwner();statement.executeBatch();}
        } finally {query.close();}
    }
    List<List<String>> readDoris(long source,String database,String table,String release,List<WarehouseDefinition.Column> columns) throws Exception {
        var query=factory.createDbQuery(property(source));
        try(Connection connection=query.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT "+fields(columns)+" FROM "+WarehouseSql.qualified(database,table)+" WHERE `_qdata_release_id`=? ORDER BY `_qdata_row_number`")) {
            statement.setString(1,release);statement.setQueryTimeout(60);try(var result=statement.executeQuery()){DorisServingSchema.verify(result.getMetaData(),columns);return rows(result,columns.size());}
        } finally {query.close();}
    }
    private static List<List<String>> read(Connection connection,String sql,int columns) throws Exception {try(Statement statement=connection.createStatement()){statement.setQueryTimeout(120);try(var result=statement.executeQuery(sql)){return rows(result,columns);}}}
    private static List<List<String>> rows(ResultSet result,int columns) throws Exception {List<List<String>> rows=new ArrayList<>();while(result.next()){List<String> row=new ArrayList<>();for(int i=1;i<=columns;i++)row.add(result.getString(i));rows.add(row);}return rows;}
    static String fields(List<WarehouseDefinition.Column> columns){return String.join(",",columns.stream().map(column->WarehouseSql.id(column.getName())).toList());}
    private static List<String> signature(List<WarehouseDefinition.Column> columns){return columns.stream().map(column->column.getName()+":"+WarehouseSql.type(column.getType())).toList();}
}
