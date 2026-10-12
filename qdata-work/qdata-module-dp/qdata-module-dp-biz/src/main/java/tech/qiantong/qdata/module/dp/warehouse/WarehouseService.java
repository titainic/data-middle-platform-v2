package tech.qiantong.qdata.module.dp.warehouse;

import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.module.da.api.service.asset.IDaAssetApiOutService;
import tech.qiantong.qdata.module.da.api.asset.dto.DaAssetReqDTO;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import static tech.qiantong.qdata.module.dp.warehouse.WarehouseSql.*;

@Service
@RequiredArgsConstructor
public class WarehouseService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final IDaDatasourceApiService datasources;
    private final DataSourceFactory factory;
    private final WarehouseSpaceService spaces;
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private IDaAssetApiOutService assets;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<tech.qiantong.qdata.module.da.api.service.DataResourceAccess> resources;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"hive-warehouse");t.setDaemon(true);return t;});
    private final AtomicBoolean running=new AtomicBoolean();

    @PostConstruct
    public void recover() {
        jdbc.update("UPDATE dp_warehouse_run SET status='INTERRUPTED',message='服务重启，执行状态未知；请核对 Hive 结果后重试',finished_at=NOW(6) WHERE status IN ('RUNNING','QUEUED') AND mode<>'MANAGED'");
    }
    @PreDestroy public void close() { worker.shutdownNow(); }
    private List<WarehouseDefinition> allDefinitions() {
        return jdbc.query("SELECT id,warehouse_config FROM dp_model WHERE del_flag='0' AND warehouse_config IS NOT NULL ORDER BY id",(rs,n)->{
            var d=JSON.parseObject(rs.getString(2),WarehouseDefinition.class); d.setId(rs.getLong(1)); return d;
        });
    }
    public List<WarehouseDefinition> list() {
        Set<Long> visible = spaces.spaces().stream().map(WarehouseSpaceService.Space::getId).collect(Collectors.toSet());
        Set<Long> legacy = spaces.legacyModelIds();
        var result=allDefinitions().stream().filter(d -> d.getSpaceId() == null ? legacy.contains(d.getId()) : visible.contains(d.getSpaceId())).toList();
        Map<Long,String> batches=new HashMap<>();jdbc.query("SELECT model_id,batch_id FROM dp_warehouse_batch_step WHERE model_id IS NOT NULL",rs->{batches.put(rs.getLong(1),rs.getString(2));});
        result.forEach(d->d.setManagedBatchId(batches.get(d.getId())));return result;
    }
    WarehouseDefinition getInternal(long id) {
        return readDefinition(id,false);
    }
    private WarehouseDefinition readDefinition(long id,boolean lock) {
        var matches = jdbc.query("SELECT warehouse_config FROM dp_model WHERE id=? AND del_flag='0' AND warehouse_config IS NOT NULL"+(lock?" FOR UPDATE":""), (rs,n) -> {
            var definition = JSON.parseObject(rs.getString(1), WarehouseDefinition.class); definition.setId(id); return definition;
        }, id);
        require(matches.size() == 1, "分层模型不存在");
        return matches.get(0);
    }
    public WarehouseDefinition get(long id) {
        WarehouseDefinition definition = getInternal(id);
        if (definition.getSpaceId() == null) spaces.authorizeLegacy(id);
        else spaces.authorize(definition.getSpaceId());
        return definition;
    }
    public List<Map<String,Object>> datasourceList() {
        var allowed=resources.getObject().visibleDatasourceIds();
        if(allowed.isEmpty())return List.of();
        return jdbc.queryForList("SELECT id,datasource_name AS name FROM da_datasource WHERE UPPER(datasource_type)='HIVE' AND del_flag='0' AND id IN ("+String.join(",",Collections.nCopies(allowed.size(),"?"))+") ORDER BY id",allowed.toArray());
    }
    private DbQueryProperty property(long id) {
        var ds=datasources.getDatasourceById(id);
        require(ds!=null && "HIVE".equalsIgnoreCase(ds.getDatasourceType()),"请选择有效的 Hive 数据源");
        var p=new DbQueryProperty(ds.getDatasourceType(),ds.getIp(),ds.getPort(),ds.getDatasourceConfig());
        WarehouseSql.id(p.getDbName()); return p;
    }
    DbQueryProperty property(WarehouseDefinition definition) {
        DbQueryProperty property;
        if (definition.getSpaceId() != null) {
            var space = spaces.getInternal(definition.getSpaceId());
            require(Objects.equals(space.getDatasourceId(), definition.getDatasourceId()), "模型必须使用数仓空间绑定的 Hive 连接");
            String database = space.getDatabases().get(definition.getLayer());
            require(database != null, "空间未配置该分层数据库");
            require(definition.getDatabaseName() == null || definition.getDatabaseName().isBlank()
                    || database.equalsIgnoreCase(definition.getDatabaseName()), "模型目标数据库与分层空间绑定不一致");
            definition.setDatabaseName(database);
            property = spaces.property(space);
        } else {
            property = property(definition.getDatasourceId());
            if (definition.getDatabaseName() == null || definition.getDatabaseName().isBlank())
                definition.setDatabaseName(property.getDbName());
        }
        definition.setDatabaseName(tech.qiantong.qdata.common.database.core.HiveTableIdentity.identifier(definition.getDatabaseName()));
        property.setDbName(definition.getDatabaseName());
        return property;
    }
    public Map<String,Object> validate(WarehouseDefinition d) {
        if (d.getId() != null) get(d.getId());
        if (d.getSpaceId() != null) spaces.authorize(d.getSpaceId());
        require(d.getId() != null || d.getSpaceId() != null, "新建库级模型请先选择数仓空间");
        if (d.getUpstreamIds() != null) for (Long sourceId : d.getUpstreamIds()) {
            require(sourceId != null, "上游模型标识不能为空"); get(sourceId);
        }
        if (d.getDimensions() != null) for (var relation : d.getDimensions()) {
            require(relation != null && relation.getModelId() != null, "维度模型标识不能为空"); get(relation.getModelId());
        }
        return validateInternal(d);
    }
    /** Interactive preview checks current source rows; execution repeats the scan before INSERT. */
    public Map<String,Object> validateWithData(WarehouseDefinition d) {
        Map<String,Object> result=new LinkedHashMap<>(validate(d));
        if("ODS".equals(d.getLayer())) {result.put("dataCheckStatus","NOT_REQUIRED");return result;}
        String preflight=numericPreflightSql(d);
        if(preflight==null) {result.put("dataCheckStatus","NOT_REQUIRED");return result;}
        var query=factory.createDbQuery(property(d));
        try(Connection connection=query.getConnection();Statement statement=connection.createStatement()) {
            statement.setQueryTimeout(120);
            for(Long sourceId:d.getUpstreamIds()) {
                var source=getInternal(sourceId);var sourceProperty=property(source);
                boolean exists=false;
                try(ResultSet tables=statement.executeQuery("SHOW TABLES IN "+id(sourceProperty.getDbName())
                        +" LIKE "+literal(source.getTableName()))) {
                    while(tables.next())if(source.getTableName().equalsIgnoreCase(tables.getString(1)))exists=true;
                }
                if(!exists) {result.put("dataCheckStatus","DEFERRED");return result;}
            }
            verifyNumericCastsBeforeInsert(statement,d);
            result.put("dataCheckStatus","PASS");
            return result;
        } catch(SQLException failure) {
            throw new IllegalArgumentException("Hive 当前数据的数值 CAST 预检未完成；模型尚未保存",failure);
        } finally {query.close();}
    }
    private Map<String,Object> validateInternal(WarehouseDefinition d) {
        require(d.getName()!=null && !d.getName().isBlank() && d.getName().length()<=256,"请填写模型中文名称（最多 256 字）");
        require(LAYERS.contains(d.getLayer()),"仅支持 ODS→DWD→DWS→DWT→ADS 五层");
        id(d.getTableName());
        if (d.getSpaceId() == null)
            require(d.getTableName().toUpperCase(Locale.ROOT).startsWith(d.getLayer()+"_"),"旧模型表名必须以 "+d.getLayer()+"_ 开头");
        require(d.getDatasourceId()!=null,"请选择 Hive 数据源");
        var p=property(d);
        if(d.getSpaceId()!=null && d.getDatasetId()!=null) {
            var codes=jdbc.queryForList("SELECT code FROM dp_warehouse_dataset WHERE id=? AND space_id=? AND layer_code=?",
                    String.class,d.getDatasetId(),d.getSpaceId(),d.getLayer());
            require(codes.size()==1,"数据集版本必须属于相同项目空间和分层");
            require(WarehouseSpaceService.sameLogicalDataset(codes.get(0),d.getTableName()),
                    "已有数据集只允许登记同名逻辑表或其版本表；新表请新建数据集");
        }
        require(d.getGrain()!=null && !d.getGrain().isBlank(),"请明确数据粒度（一行代表什么）");
        require(d.getUpstreamIds()!=null && d.getDimensions()!=null,"上游和维度关系不能为空对象");
        validateColumns(d);
        boolean theme="DWT".equals(d.getLayer());
        require(theme ? Set.of("FACT","DIMENSION").contains(d.getRole()) : ("ADS".equals(d.getLayer()) ? "APPLICATION".equals(d.getRole()) : "DETAIL".equals(d.getRole())),"模型角色与所属层不匹配；DWT 应选择事实表或维度表");
        if(theme || "ADS".equals(d.getLayer())) require(d.getTopic()!=null && !d.getTopic().isBlank(),"DWT/ADS 必须填写业务主题");
        var models=allDefinitions();
        for(var other:models) if(!Objects.equals(other.getId(),d.getId())) {
            if (Objects.equals(other.getDatasourceId(),d.getDatasourceId()) && other.getTableName().equalsIgnoreCase(d.getTableName()))
                require(!property(other).getDbName().equalsIgnoreCase(p.getDbName()), "该 Hive 库表已被其他模型登记");
        }
        Set<String> allowed=new LinkedHashSet<>();
        if("ODS".equals(d.getLayer())) {
            require(d.getUpstreamIds().isEmpty(),"ODS 只接受原始来源，不允许引用数仓上层模型");
            require(d.getSelectSql()==null || d.getSelectSql().isBlank(),"ODS 原始数据不可配置加工 SQL");
            if(d.getRawTable()!=null && !d.getRawTable().isBlank()) {
                String raw=canonical(d.getRawTable(),p.getDbName());
                require(!spaces.isManagedNamespace(d.getDatasourceId(), raw.substring(0,raw.indexOf('.'))),
                        "ODS 原始来源不能读取已绑定的数仓分层库，请通过原始接入任务采集");
                require(!raw.equals(canonical(d.getTableName(),p.getDbName())),"ODS 来源不能是自身");
                String rawName=raw.substring(raw.indexOf('.')+1).toUpperCase(Locale.ROOT);
                require(LAYERS.stream().noneMatch(l->rawName.startsWith(l+"_")),"ODS 原始来源不能引用任何数仓分层表");
                for (var existing : models) if (Objects.equals(existing.getDatasourceId(), d.getDatasourceId()))
                    require(!raw.equals(canonical(existing.getTableName(), property(existing).getDbName())), "ODS 原始来源不能引用已登记的数仓模型");
            }
        } else {
            require(!d.getUpstreamIds().isEmpty(),"请选择上一层模型");
            require(new HashSet<>(d.getUpstreamIds()).size()==d.getUpstreamIds().size(),"上游模型不能重复");
            String expected=LAYERS.get(LAYERS.indexOf(d.getLayer())-1);
            for(Long sourceId:d.getUpstreamIds()) {
                var source=getInternal(sourceId);
                require(expected.equals(source.getLayer()),"禁止跨层取数："+d.getLayer()+" 只能读取 "+expected+"，不能读取 "+source.getLayer());
                require(Objects.equals(d.getDatasourceId(),source.getDatasourceId()),"上游必须使用同一个已登记 Hive 执行连接");
                require(Objects.equals(d.getSpaceId(),source.getSpaceId()), "加工来源必须属于相同项目、环境和数仓空间");
                allowed.add(canonical(source.getTableName(),property(source).getDbName()));
            }
            Set<String> actual=readTables(d.getSelectSql(),p.getDbName());
            require(actual.equals(allowed),"SQL 实际来源与所选上一层模型不一致。SQL："+actual+"；已选："+allowed);
        }
        Set<String> relationColumns=new HashSet<>();
        require(d.getDimensions().isEmpty() || (theme && "FACT".equals(d.getRole())),"只有 DWT 事实表可配置星型维度关系");
        for(var rel:d.getDimensions()) {
            var dim=getInternal(rel.getModelId());
            require("DWT".equals(dim.getLayer()) && "DIMENSION".equals(dim.getRole()),"星型关系必须指向 DWT 维度表");
            require(Objects.equals(dim.getDatasourceId(),d.getDatasourceId()),"事实与维度必须在同一个 Hive 数据源");
            require(Objects.equals(dim.getSpaceId(), d.getSpaceId()), "事实和维度必须属于相同数仓空间");
            var local=column(d,rel.getColumn()); var remote=column(dim,rel.getTargetColumn());
            require(dim.getColumns().stream().filter(WarehouseDefinition.Column::isKey).count()==1,"当前星型关系使用单列唯一维度键；复合维度请先生成统一业务键");
            require(remote.isKey(),"关联的维度字段必须标记为业务键");
            require(type(local.getType()).equals(type(remote.getType())),"事实外键与维度键类型必须一致");
            require(relationColumns.add(rel.getColumn().toLowerCase(Locale.ROOT)),"同一个字段不能重复配置维度关系");
        }
        if(theme) require(d.getColumns().stream().anyMatch(WarehouseDefinition.Column::isKey),"DWT 需标记业务键；Hive 不强制主键，执行时会校验非空和唯一性");
        if(theme && "FACT".equals(d.getRole())) require(!d.getDimensions().isEmpty(),"DWT 事实表至少关联一个主题维度");
        String insert=("ODS".equals(d.getLayer()) && (d.getRawTable()==null || d.getRawTable().isBlank()))?"":insert(d,p.getDbName());
        return Map.of("ddl",ddl(d,p.getDbName()),"insertSql",insert,"sources",allowed,"database",p.getDbName(),"qualifiedTable",qualified(p.getDbName(),d.getTableName()));
    }
    private WarehouseDefinition.Column column(WarehouseDefinition d,String name) {
        return d.getColumns().stream().filter(c->c.getName().equalsIgnoreCase(name)).findFirst().orElseThrow(()->new IllegalArgumentException("字段不存在："+name));
    }
    public synchronized WarehouseDefinition save(WarehouseDefinition d,String user,long userId) {
        require(!running.get(),"分层任务执行中，请执行结束后再修改模型");
        if (d.getId()!=null) require(!managedModel(d.getId()), "受管批次模型已冻结，请修改模板并创建新批次");
        validate(d);
        d.setTableName(d.getTableName().toLowerCase(Locale.ROOT));
        return transaction.execute(status->{
            if(d.getId()!=null) {
                // A current locking read also protects against another application instance.
                var old=readDefinition(d.getId(),true);
                property(old);
                require(d.getRevision()==old.getRevision(),"模型已被其他用户修改，请刷新后重试");
                require(old.getLayer().equals(d.getLayer()) && old.getTableName().equals(d.getTableName()) && old.getDatasourceId().equals(d.getDatasourceId()),"已有模型的分层、表名和数据源不可更改，请另建模型");
                require(Objects.equals(old.getSpaceId(),d.getSpaceId()) && Objects.equals(old.getDatabaseName(),d.getDatabaseName())
                        && Objects.equals(old.getDatasetId(),d.getDatasetId()), "已有模型的空间、数据库和数据集不可更改，请创建新版本模型");
                Long built=jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_run WHERE model_id=?",Long.class,d.getId());
                if(built>0 && "ODS".equals(d.getLayer())) require(Objects.equals(old.getRawTable(),d.getRawTable()),"已有执行记录，ODS 原始来源不可改写；请另建模型保留真实血缘");
                require(old.getRole().equals(d.getRole()),"已有模型不能修改角色，请另建模型");
                if(built>0) require(structure(old).equals(structure(d)),"已有执行记录，不能直接修改物理结构；请新建版本表，避免损坏数据");
                // Keep dimension relationships valid when a referenced key is edited.
                for(var downstream:allDefinitions()) if(!Objects.equals(downstream.getId(),d.getId())) for(var rel:downstream.getDimensions())
                    if(Objects.equals(rel.getModelId(),d.getId())) require(column(d,rel.getTargetColumn()).isKey(),"该业务键已被主题事实表引用，不可取消");
                d.setRevision(old.getRevision()+1);
            } else d.setRevision(1);
            Long layerId=jdbc.queryForObject("SELECT id FROM dm_data_layer WHERE eng_name=? AND del_flag='0'",Long.class,d.getLayer());
            String tableType="DIMENSION".equals(d.getRole())?"3":("APPLICATION".equals(d.getRole())?"4":"1");
            if(d.getId()==null) {
                GeneratedKeyHolder key=new GeneratedKeyHolder();
                jdbc.update(conn->{var st=conn.prepareStatement("INSERT INTO dp_model(model_name,model_comment,status,create_type,datasource_id,data_layer_id,table_type,table_case,description,create_by,creator_id,warehouse_config) VALUES (?,?,'1','1',?,?,?,'2',?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
                    st.setString(1,d.getTableName());st.setString(2,d.getName());st.setLong(3,d.getDatasourceId());st.setLong(4,layerId);st.setString(5,tableType);st.setString(6,d.getDescription());st.setString(7,user);st.setLong(8,userId);st.setString(9,JSON.toJSONString(d));return st;},key);
                d.setId(key.getKey().longValue());
            }
            spaces.registerMaterialization(d, d.getSpaceId() == null ? null : spaces.getInternal(d.getSpaceId()), user);
            jdbc.update("UPDATE dp_model SET model_comment=?,description=?,database_name=?,dataset_id=?,warehouse_config=?,update_by=?,updater_id=?,update_time=NOW(6) WHERE id=?",d.getName(),d.getDescription(),d.getDatabaseName(),d.getDatasetId(),JSON.toJSONString(d),user,userId,d.getId());
            // Preserve column IDs and any references when the logical fields are unchanged.
            Map<String,Long> existingColumns = new HashMap<>();
            jdbc.query("SELECT id,eng_name FROM dp_model_column WHERE model_id=? AND del_flag='0'", (org.springframework.jdbc.core.RowCallbackHandler)rs ->
                    existingColumns.put(rs.getString("eng_name").toLowerCase(Locale.ROOT),rs.getLong("id")), d.getId());
            int order=0;
            for(var c:ordered(d)) {
                Long columnId = existingColumns.remove(c.getName().toLowerCase(Locale.ROOT));
                if (columnId == null) jdbc.update("INSERT INTO dp_model_column(model_id,eng_name,cn_name,column_type,pk_flag,nullable_flag,sort_order,remark,create_by,creator_id) VALUES (?,?,?,?,?,'0',?,?,?,?)",d.getId(),c.getName(),c.getComment(),type(c.getType()),c.isKey()?"1":"0",++order,c.getSource(),user,userId);
                else jdbc.update("UPDATE dp_model_column SET cn_name=?,column_type=?,pk_flag=?,sort_order=?,remark=? WHERE id=? AND model_id=?",c.getComment(),type(c.getType()),c.isKey()?"1":"0",++order,c.getSource(),columnId,d.getId());
            }
            existingColumns.values().forEach(columnId -> jdbc.update("UPDATE dp_model_column SET del_flag='1' WHERE id=? AND model_id=?",columnId,d.getId()));
            return d;
        });
    }
    private String structure(WarehouseDefinition d) {
        return d.getStorage()+ordered(d).stream().map(c->c.getName().toLowerCase(Locale.ROOT)+":"+type(c.getType())+":"+c.isPartition()).collect(Collectors.joining("|"));
    }
    public List<Map<String,Object>> logs(Long modelId) {return logs(modelId,list().stream().map(WarehouseDefinition::getId).toList());}
    public List<Map<String,Object>> logs(Long modelId,List<Long> permittedModelIds) {
        if (modelId != null) {
            get(modelId);
            return jdbc.queryForList("SELECT * FROM dp_warehouse_run WHERE model_id=? ORDER BY id DESC LIMIT 50",modelId);
        }
        var ids = permittedModelIds;
        if (ids.isEmpty()) return List.of();
        return jdbc.queryForList("SELECT * FROM dp_warehouse_run WHERE model_id IN (" + String.join(",", Collections.nCopies(ids.size(),"?")) + ") ORDER BY id DESC LIMIT 100", ids.toArray());
    }
    private boolean managedModel(long modelId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_batch_step WHERE model_id=?",Long.class,modelId)>0;
    }
    public synchronized Map<String,Object> start(long modelId,String mode,String user) {
        require(Set.of("DDL","LOAD","CHAIN").contains(mode),"不支持的执行方式");
        require(!managedModel(modelId),"受管版本请从对应的数据开发任务或批次入口执行");
        require(!running.get(),"已有分层任务执行中，请等待完成");
        LinkedHashMap<Long,WarehouseDefinition> plan=new LinkedHashMap<>();
        collect(get(modelId),mode.equals("CHAIN"),plan,new HashSet<>());
        plan.values().forEach(this::validate);
        String batch=UUID.randomUUID().toString();
        for(var d:plan.values()) jdbc.update("INSERT INTO dp_warehouse_run(batch_id,model_id,revision,mode,status,definition_json,create_by) VALUES (?,?,?,?,'QUEUED',?,?)",batch,d.getId(),d.getRevision(),mode,runSnapshot(d),user);
        running.set(true);
        worker.submit(()->{
            try {
                for(var d:plan.values()) {
                    jdbc.update("UPDATE dp_warehouse_run SET status='RUNNING',started_at=NOW(6) WHERE batch_id=? AND model_id=?",batch,d.getId());
                    try { execute(d,mode,batch); }
                    catch(Exception e) {
                        String message=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
                        jdbc.update("UPDATE dp_warehouse_run SET status='FAILED',message=?,finished_at=NOW(6) WHERE batch_id=? AND model_id=?",message,batch,d.getId());
                        jdbc.update("UPDATE dp_warehouse_run SET status='BLOCKED',message='上游失败，未执行',finished_at=NOW(6) WHERE batch_id=? AND status='QUEUED'",batch);break;
                    }
                }
            } finally { running.set(false); }
        });
        return Map.of("batchId",batch,"models",plan.keySet());
    }
    String runSnapshot(WarehouseDefinition d) {
        var snapshot=JSON.parseObject(JSON.toJSONString(d));
        var sources=new LinkedHashMap<String,Object>();
        for(Long sourceId:d.getUpstreamIds()) {
            var source=getInternal(sourceId);
            sources.put(canonical(source.getTableName(),property(source).getDbName()), Map.of("modelId",sourceId,"revision",source.getRevision()));
        }
        snapshot.put("upstreamVersions",sources);
        return snapshot.toJSONString();
    }
    private void collect(WarehouseDefinition d,boolean recursive,Map<Long,WarehouseDefinition> plan,Set<Long> path) {
        require(path.add(d.getId()),"检测到循环依赖");
        if(recursive) { for(Long id:d.getUpstreamIds()) if(!plan.containsKey(id)) collect(getInternal(id),true,plan,path);
            for(var rel:d.getDimensions()) if(!plan.containsKey(rel.getModelId())) collect(getInternal(rel.getModelId()),true,plan,path); }
        plan.put(d.getId(),d);path.remove(d.getId());
    }
    void execute(WarehouseDefinition d,String mode,String batch) throws Exception {
        execute(d,mode,batch,null);
    }
    void execute(WarehouseDefinition d,String mode,String batch,DbQueryProperty compute) throws Exception {
        validateInternal(d); var p=compute==null?property(d):compute.copy();p.setDbName(d.getDatabaseName());var query=factory.createDbQuery(p);
        String table=qualified(p.getDbName(),d.getTableName());StringBuilder executed=new StringBuilder();
        try(Connection conn=query.getConnection();Statement st=conn.createStatement()) {
            st.setQueryTimeout(1800);
            boolean exists;
            try(ResultSet rs=st.executeQuery("SHOW TABLES IN "+id(p.getDbName())+" LIKE "+literal(d.getTableName()))) { exists=rs.next(); }
            if(exists) {
                // Refuse to adopt an unrelated existing table; only our recorded DDL owns the target.
                long known=jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_run WHERE model_id=? AND ddl_created=TRUE",Long.class,d.getId());
                require(known>0,"目标表已存在但无本建模入口的建表记录；请使用新表名，避免覆盖已有数据");
            } else {
                String sql=ddl(d,p.getDbName());recordSql(batch,d,executed,sql);st.execute(sql);
                jdbc.update("UPDATE dp_warehouse_run SET ddl_created=TRUE,message='DDL完成；后续处理中' WHERE batch_id=? AND model_id=?",batch,d.getId());
                registerAsset(d);
            }
            verifySchema(st,d,table);
            registerAsset(d);
            if(!mode.equals("DDL")) {
                if("ODS".equals(d.getLayer()) && count(st,table)>0) {
                    finish(batch,d,count(st,table),"DDL完成；ODS 已有原始数据，保持不变");return;
                }
                if(!"ODS".equals(d.getLayer())) {
                    require(count(st,table)==0,"目标表已有数据，已停止加工以保留现有数据。请新建版本表；Hive 数据删除仅由用户手工操作。");
                }
                if("ODS".equals(d.getLayer()) && (d.getRawTable()==null || d.getRawTable().isBlank())) throw new IllegalArgumentException("ODS 尚无数据：请配置原始来源表或通过数据集成采集后再执行下游");
                for(Long sourceId:d.getUpstreamIds()) {
                    var source=getInternal(sourceId);
                    var latest=jdbc.queryForList("SELECT revision,status FROM dp_warehouse_run WHERE model_id=? AND mode<>'DDL' ORDER BY id DESC LIMIT 1",sourceId);
                    require(!latest.isEmpty() && ((Number)latest.get(0).get("revision")).intValue()==source.getRevision() && "SUCCESS".equals(latest.get(0).get("status")),"上游尚未完成当前版本的数据加工："+source.getTableName()+"；请执行整条链路");
                }
                if("ODS".equals(d.getLayer())) {
                    String[] raw=canonical(d.getRawTable(),p.getDbName()).split("\\.");
                    verifySchema(st,d,qualified(raw[0],raw[1]));
                }
                // Force an explicit, unambiguous target-field contract before INSERT's positional mapping.
                try(ResultSet rs=st.executeQuery(schemaCheck(d,p.getDbName()))) {
                    var md=rs.getMetaData();var columns=ordered(d);require(md.getColumnCount()==columns.size(),"SELECT 输出字段数与模型不一致");
                    for(int i=0;i<columns.size();i++) {String label=md.getColumnLabel(i+1);label=label.substring(label.lastIndexOf('.')+1);require(label.equalsIgnoreCase(columns.get(i).getName()),"SELECT 第 "+(i+1)+" 列需命名为 "+columns.get(i).getName()+"，实际为 "+label);}
                }
                if(!"ODS".equals(d.getLayer())) verifyNumericCastsBeforeInsert(st,d);
                if(d.getColumns().stream().anyMatch(WarehouseDefinition.Column::isPartition)) {
                    st.execute("SET hive.exec.dynamic.partition=true");st.execute("SET hive.exec.dynamic.partition.mode=nonstrict");
                }
                String sql=insert(d,p.getDbName());recordSql(batch,d,executed,sql);st.execute(sql);
                verifyKeysAndRelations(st,d,p.getDbName());
            }
            finish(batch,d,count(st,table),mode.equals("DDL")?"DDL完成；结构已验证":"DDL完成；加工及质量校验通过");
        } finally { query.close(); }
    }
    private String numericPreflightSql(WarehouseDefinition definition) {
        String guarded=WarehouseSql.numericCastPreflight(definition.getSelectSql());
        if(guarded!=null)return guarded;
        boolean floatingOutput=definition.getColumns()!=null && definition.getColumns().stream()
                .anyMatch(column -> "FLOAT".equalsIgnoreCase(column.getType()) || "DOUBLE".equalsIgnoreCase(column.getType()));
        return floatingOutput?definition.getSelectSql():null;
    }
    private void verifyNumericCastsBeforeInsert(Statement statement,WarehouseDefinition definition) throws SQLException {
        String preflight=numericPreflightSql(definition);
        if(preflight==null)return;
        try(ResultSet rows=statement.executeQuery(preflight)) {
            int columns=rows.getMetaData().getColumnCount();
            while(rows.next())for(int i=1;i<=columns;i++) {
                Object value=rows.getObject(i);
                if((value instanceof Double && !Double.isFinite((Double)value))
                        || (value instanceof Float && !Float.isFinite((Float)value)))
                    throw new WarehouseInputRejected("来源数据产生 NaN 或 Infinity；已在写入目标前拒绝，Hive 数据保持不变",null);
            }
        } catch(SQLException failure) {
            if(failure.getMessage()!=null && failure.getMessage().contains("ASSERT_TRUE"))
                throw new WarehouseInputRejected("来源数据的动态数值 CAST 发生无效值或溢出；已在写入目标前拒绝，Hive 数据保持不变",failure);
            throw failure;
        }
    }
    private void registerAsset(WarehouseDefinition d) {
        long existing = jdbc.queryForObject("SELECT COUNT(*) FROM da_asset WHERE datasource_id=? AND database_name=? AND table_name=? AND del_flag='0'", Long.class,
                d.getDatasourceId(), d.getDatabaseName(), d.getTableName());
        require(existing <= 1, "当前物理表关联了多个资产，请核对资产身份后重试");
        if (existing == 1) return;
        var asset=new DaAssetReqDTO();asset.setSource("2");asset.setModelId(d.getId());asset.setDatasourceId(d.getDatasourceId());asset.setDatabaseName(d.getDatabaseName());asset.setDatasetId(d.getDatasetId());asset.setFieldCount((long)d.getColumns().size());
        if (d.getSpaceId() != null) {
            var space = spaces.getInternal(d.getSpaceId());
            asset.setProjectId(space.getProjectId()); asset.setProjectCode(space.getProjectCode());
        }
        assets.insertDaAsset(asset);
    }
    private void recordSql(String batch,WarehouseDefinition d,StringBuilder executed,String sql) {
        executed.append(sql).append(";\n");jdbc.update("UPDATE dp_warehouse_run SET sql_text=? WHERE batch_id=? AND model_id=?",executed.toString(),batch,d.getId());
    }
    void verifySchema(Statement st,WarehouseDefinition d,String table) throws SQLException {
        LinkedHashMap<String,String> actual=new LinkedHashMap<>();
        try(ResultSet rs=st.executeQuery("DESCRIBE "+table)) {while(rs.next()) {String name=rs.getString(1),type=rs.getString(2);if(name!=null && !name.isBlank() && !name.trim().startsWith("#") && type!=null && !type.isBlank())actual.putIfAbsent(name.trim().toLowerCase(Locale.ROOT),type.replaceAll("\\s+", "").toUpperCase(Locale.ROOT));}}
        LinkedHashMap<String,String> expected=new LinkedHashMap<>();ordered(d).forEach(c->expected.put(c.getName().toLowerCase(Locale.ROOT),type(c.getType())));
        require(actual.equals(expected) && new ArrayList<>(actual.keySet()).equals(new ArrayList<>(expected.keySet())),"Hive 实际字段结构与模型不一致，停止写入。实际："+actual+"；模型："+expected);
    }
    private long count(Statement st,String table) throws SQLException {try(ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM "+table)){rs.next();return rs.getLong(1);}}
    private void verifyKeysAndRelations(Statement st,WarehouseDefinition d,String db) throws SQLException {
        var keys=d.getColumns().stream().filter(WarehouseDefinition.Column::isKey).map(c->id(c.getName())).toList();
        String table=qualified(db,d.getTableName());
        if(!keys.isEmpty()) {
            String key=String.join(",",keys);
            try(ResultSet rs=st.executeQuery("SELECT "+key+" FROM "+table+" GROUP BY "+key+" HAVING COUNT(*)>1 OR "+keys.stream().map(k->k+" IS NULL").collect(Collectors.joining(" OR "))+" LIMIT 1")){if(rs.next())throw new WarehouseOutputRejected("业务键存在重复或空值；数据已保留且不能作为成功上游。请修正 SQL 并新建版本表验证。");}
        }
        for(var rel:d.getDimensions()) {
            var dim=getInternal(rel.getModelId());
            String sql="SELECT 1 FROM "+table+" f LEFT JOIN "+qualified(property(dim).getDbName(),dim.getTableName())+" d ON f."+id(rel.getColumn())+"=d."+id(rel.getTargetColumn())+" WHERE f."+id(rel.getColumn())+" IS NOT NULL AND d."+id(rel.getTargetColumn())+" IS NULL LIMIT 1";
            try(ResultSet rs=st.executeQuery(sql)){if(rs.next())throw new WarehouseOutputRejected("星型关系存在无法匹配的维度键："+rel.getColumn()+"；输出已保留，禁止下游消费");}
        }
    }
    void finish(String batch,WarehouseDefinition d,long rows,String message) {
        transaction.executeWithoutResult(status -> {
            int updated = jdbc.update("UPDATE da_asset SET data_count=?,field_count=? WHERE datasource_id=? AND database_name=? AND table_name=? AND del_flag='0'", rows, d.getColumns().size(), d.getDatasourceId(), d.getDatabaseName(), d.getTableName());
            require(updated <= 1 && jdbc.queryForObject("SELECT COUNT(*) FROM da_asset WHERE datasource_id=? AND database_name=? AND table_name=? AND data_count=? AND field_count=? AND del_flag='0'",
                    Long.class, d.getDatasourceId(), d.getDatabaseName(), d.getTableName(), rows, d.getColumns().size()) == 1,
                    "Hive 结果已保留，但资产统计未正确登记，请核对元数据后重试");
            jdbc.update("UPDATE dp_warehouse_run SET status='SUCCESS',row_count=?,message=?,finished_at=NOW(6) WHERE batch_id=? AND model_id=?",rows,message,batch,d.getId());
        });
    }
    public List<Map<String,Object>> data(long modelId) {
        var d=get(modelId);var p=property(d);var q=factory.createDbQuery(p);
        try {
            return q.queryList("SELECT * FROM "+qualified(p.getDbName(),d.getTableName())+" LIMIT 50").stream().map(row -> {
                Map<String,Object> result = new LinkedHashMap<>();
                row.forEach((key,value) -> result.put(key.substring(key.lastIndexOf('.')+1), value));
                return result;
            }).toList();
        } finally {q.close();}
    }
}
