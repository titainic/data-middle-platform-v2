package tech.qiantong.qdata.module.dp.warehouse;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.core.HiveTableIdentity;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.util.*;
import static tech.qiantong.qdata.module.dp.warehouse.WarehouseSql.require;

/** Explicit catalog registration and project/environment/database ownership. */
@Service
@RequiredArgsConstructor
public class WarehouseSpaceService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final IDaDatasourceApiService datasources;
    private final DataSourceFactory factory;

    @Data public static class Engine {
        private Long id;
        private String name;
        private Long datasourceId;
        private String catalogKey;
    }

    @Data public static class Space {
        private Long id;
        private String name;
        private Long projectId;
        private String projectName;
        private String projectCode;
        private String environmentCode;
        private Long engineId;
        private String engineName;
        private Long datasourceId;
        private int revision;
        private Map<String,Boolean> capabilities = new LinkedHashMap<>();
        private Map<String,String> databases = new LinkedHashMap<>();
    }

    boolean administrator() {
        return SecurityUtils.isGlobalAdministrator();
    }

    boolean canManage() {
        return administrator() || (SecurityUtils.hasPermi("dp:model:edit")
                && SecurityUtils.hasPermi("da:dataSource:edit") && SecurityUtils.hasPermi("att:project:edit"));
    }

    Set<Long> legacyModelIds() {
        if (administrator()) return new HashSet<>(jdbc.queryForList("SELECT id FROM dp_model WHERE del_flag='0' AND warehouse_config IS NOT NULL", Long.class));
        var projects = projects().stream().map(p -> ((Number)p.get("id")).longValue()).toList();
        if (projects.isEmpty()) return Set.of();
        String placeholders = String.join(",", Collections.nCopies(projects.size(), "?"));
        List<Object> args = new ArrayList<>(); args.add(SecurityUtils.getUserId()); args.addAll(projects);
        Set<Long> visible = new HashSet<>(jdbc.queryForList("SELECT m.id FROM dp_model m WHERE m.del_flag='0' AND m.warehouse_config IS NOT NULL AND m.creator_id=? AND EXISTS (SELECT 1 FROM da_datasource_project_rel r WHERE r.datasource_id=m.datasource_id AND r.valid_flag='1' AND r.project_id IN (" + placeholders + "))", Long.class, args.toArray()));
        visible.addAll(jdbc.queryForList("SELECT DISTINCT m.id FROM dp_model m JOIN da_asset a ON a.datasource_id=m.datasource_id AND a.table_name=m.model_name AND a.database_name=m.database_name JOIN da_asset_project_rel r ON r.asset_id=a.id WHERE m.del_flag='0' AND m.warehouse_config IS NOT NULL AND a.del_flag='0' AND r.del_flag='0' AND r.valid_flag='1' AND r.project_id IN (" + placeholders + ")", Long.class, projects.toArray()));
        return visible;
    }

    void authorizeLegacy(Long modelId) {
        require(legacyModelIds().contains(modelId), "无权访问该旧模型，请核对模型所有者及项目授权");
    }

    public List<Map<String,Object>> projects() {
        if (administrator()) return jdbc.queryForList("SELECT id,name FROM att_project WHERE del_flag='0' AND valid_flag='1' ORDER BY id");
        return jdbc.queryForList("SELECT p.id,p.name FROM att_project p WHERE p.del_flag='0' AND p.valid_flag='1' AND (p.manager_id=? OR EXISTS (SELECT 1 FROM att_project_user_rel r WHERE r.project_id=p.id AND r.user_id=? AND r.del_flag='0' AND r.valid_flag='1')) ORDER BY p.id",
                SecurityUtils.getUserId(), SecurityUtils.getUserId());
    }

    private boolean projectAccessible(Long projectId) {
        return projectId != null && projects().stream().anyMatch(p -> ((Number)p.get("id")).longValue() == projectId);
    }

    private boolean sourceAssigned(Long sourceId, Long projectId) {
        return administrator() || sourceExplicitlyAssigned(sourceId, projectId);
    }

    private boolean sourceExplicitlyAssigned(Long sourceId, Long projectId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM da_datasource_project_rel WHERE datasource_id=? AND project_id=? AND valid_flag='1'",
                Long.class, sourceId, projectId) > 0;
    }

    public List<Engine> engines() {
        List<Engine> engines = jdbc.query("SELECT id,name,datasource_id,catalog_key FROM dp_warehouse_engine ORDER BY id", (rs,n) -> {
            Engine engine = new Engine(); engine.setId(rs.getLong(1)); engine.setName(rs.getString(2));
            engine.setDatasourceId(rs.getLong(3)); engine.setCatalogKey(rs.getString(4)); return engine;
        });
        if (administrator()) return engines;
        Set<Long> projects = new HashSet<>();
        projects().forEach(p -> projects.add(((Number)p.get("id")).longValue()));
        return engines.stream().filter(e -> projects.stream().anyMatch(p -> sourceAssigned(e.getDatasourceId(), p))).toList();
    }

    DbQueryProperty datasourceProperty(long sourceId) {
        var source = datasources.getDatasourceById(sourceId);
        require(source != null && "Hive".equalsIgnoreCase(source.getDatasourceType()), "请选择有效的 Hive 连接");
        var property = new DbQueryProperty(source.getDatasourceType(), source.getIp(), source.getPort(), source.getDatasourceConfig());
        property.viald();
        HiveTableIdentity.identifier(property.getDbName());
        if (property.getConfig() != null)
            for (String key : List.of("serviceDiscoveryMode", "zooKeeperNamespace", "httpPath"))
                require(property.getConfig().get(key) == null || property.getConfig().get(key).toString().isBlank(),
                        "当前受管数仓使用 HiveServer2 直连，请选择已验证的直连配置");
        return property;
    }

    static String connectionFingerprint(DbQueryProperty property) {
        return tech.qiantong.qdata.common.database.core.HiveEndpointIdentity.fingerprint(property);
    }

    static String metastoreSetting(Statement statement) throws java.sql.SQLException {
        try (var rows = statement.executeQuery("SET hive.metastore.uris")) {
            require(rows.next(), "Hive未返回元数据服务身份，不能登记独立执行环境");
            String value = rows.getString(1);
            String prefix = "hive.metastore.uris=";
            // HiveServer2 returns one key=value column; Spark/Kyuubi SET returns key and value columns.
            String setting;
            if ("hive.metastore.uris".equalsIgnoreCase(value)) {
                setting = rows.getString(2);
            } else {
                require(value != null && value.toLowerCase(Locale.ROOT).startsWith(prefix),
                        "Hive未返回元数据服务身份，不能登记独立执行环境");
                setting = value.substring(prefix.length());
            }
            setting = setting == null ? "" : setting.trim();
            require(!setting.isBlank() && !"<undefined>".equalsIgnoreCase(setting),
                    "Hive元数据服务身份不可用，不能登记独立执行环境");
            return setting;
        }
    }

    public Engine registerEngine(Engine engine) {
        require(canManage(), "登记执行环境需要模型、连接及项目管理权限");
        require(engine.getId() == null && engine.getDatasourceId() != null, "执行环境登记参数不完整");
        require(engine.getName() != null && !engine.getName().isBlank() && engine.getName().length() <= 128, "请填写执行环境名称");
        require(administrator() || projects().stream().anyMatch(project -> sourceAssigned(engine.getDatasourceId(), ((Number)project.get("id")).longValue())),
                "该 Hive 连接未分配到当前用户可管理的项目");
        engine.setCatalogKey(HiveTableIdentity.identifier(engine.getCatalogKey()));
        DbQueryProperty property = datasourceProperty(engine.getDatasourceId());
        String fingerprint = connectionFingerprint(property);
        require(jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_engine WHERE datasource_id=? OR catalog_key=? OR connection_fingerprint=?",
                Long.class, engine.getDatasourceId(), engine.getCatalogKey(), fingerprint) == 0,
                "该 Hive 连接或执行环境标识已登记，请复用现有执行环境");
        for(Long existingSource:jdbc.queryForList("SELECT datasource_id FROM dp_warehouse_engine",Long.class))
            require(!tech.qiantong.qdata.common.database.core.HiveEndpointIdentity.sharesDirectEndpoint(property,datasourceProperty(existingSource)),
                    "该 Hive 地址与已登记执行环境指向同一服务，请复用现有连接");
        var query = factory.createDbQuery(property);
        try (var connection = query.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            try (var rows = statement.executeQuery("SELECT current_database(), current_user()")) {
                require(rows.next(), "Hive 执行环境未返回连接验证结果");
            }
            String metastore = metastoreSetting(statement);
            for (Long existingSource : jdbc.queryForList("SELECT datasource_id FROM dp_warehouse_engine", Long.class)) {
                var existingQuery = factory.createDbQuery(datasourceProperty(existingSource));
                try (var existingConnection = existingQuery.getConnection();
                     var existingStatement = existingConnection.createStatement()) {
                    existingStatement.setQueryTimeout(30);
                    require(!tech.qiantong.qdata.common.database.core.HiveEndpointIdentity.sharesMetastore(
                            metastore, metastoreSetting(existingStatement)),
                            "该 Hive 地址与已登记执行环境共享元数据服务，请复用现有连接");
                } finally { existingQuery.close(); }
            }
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("Hive 连接验证失败，请核对已登记连接及权限", error); }
        finally { query.close(); }
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO dp_warehouse_engine(name,datasource_id,catalog_key,connection_fingerprint,create_by) VALUES (?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, engine.getName()); statement.setLong(2, engine.getDatasourceId());
            statement.setString(3, engine.getCatalogKey()); statement.setString(4, fingerprint);
            statement.setString(5, SecurityUtils.getUsername()); return statement;
        }, key);
        engine.setId(key.getKey().longValue());
        return engine;
    }

    static Map<String,String> validateDatabases(Map<String,String> databases) {
        require(databases != null && databases.keySet().equals(new HashSet<>(WarehouseSql.LAYERS)),
                "必须完整配置 ODS、DWD、DWS、DWT、ADS 五层数据库");
        Map<String,String> normalized = new LinkedHashMap<>();
        WarehouseSql.LAYERS.forEach(layer -> normalized.put(layer, HiveTableIdentity.identifier(databases.get(layer))));
        require(new HashSet<>(normalized.values()).size() == 5, "库级分层的五个数据库不能重复");
        return normalized;
    }

    List<Space> allSpaces() {
        return jdbc.query("SELECT s.*,p.name AS project_name,p.code AS project_code,e.name AS engine_name,e.datasource_id FROM dp_warehouse_space s JOIN att_project p ON p.id=s.project_id JOIN dp_warehouse_engine e ON e.id=s.engine_id ORDER BY s.id", (rs,n) -> {
            Space space = new Space(); space.setId(rs.getLong("id")); space.setName(rs.getString("name"));
            space.setProjectId(rs.getLong("project_id")); space.setProjectName(rs.getString("project_name"));
            space.setProjectCode(rs.getString("project_code"));
            space.setEnvironmentCode(rs.getString("environment_code")); space.setEngineId(rs.getLong("engine_id"));
            space.setEngineName(rs.getString("engine_name")); space.setDatasourceId(rs.getLong("datasource_id"));
            space.setRevision(rs.getInt("revision"));
            space.setDatabases(JSON.parseObject(rs.getString("databases_json"), new TypeReference<LinkedHashMap<String,String>>() {}));
            return space;
        });
    }

    public List<Space> spaces() {
        Set<Long> visible = new HashSet<>();
        projects().forEach(project -> visible.add(((Number)project.get("id")).longValue()));
        return allSpaces().stream().filter(space -> visible.contains(space.getProjectId())
                && sourceAssigned(space.getDatasourceId(), space.getProjectId())).toList();
    }

    Space getInternal(Long id) {
        return allSpaces().stream().filter(space -> Objects.equals(space.getId(), id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("数仓空间不存在"));
    }

    boolean isManagedNamespace(Long datasourceId, String database) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_namespace n JOIN dp_warehouse_engine e ON e.id=n.engine_id WHERE e.connection_fingerprint=? AND n.database_name=?",
                Long.class, connectionFingerprint(datasourceProperty(datasourceId)), database) > 0;
    }

    void authorize(Long id) {
        if (id == null) {
            require(administrator(), "旧模型尚未归属项目空间，请联系管理员配置后再访问");
            return;
        }
        Space space = getInternal(id);
        require(projectAccessible(space.getProjectId()), "无权访问该项目的数仓空间");
        require(sourceAssigned(space.getDatasourceId(), space.getProjectId()), "该项目未获授权使用空间绑定的 Hive 连接");
    }

    DbQueryProperty property(Space space) {
        DbQueryProperty property = datasourceProperty(space.getDatasourceId());
        String expected = jdbc.queryForObject("SELECT connection_fingerprint FROM dp_warehouse_engine WHERE id=?",
                String.class, space.getEngineId());
        require(connectionFingerprint(property).equals(expected),
                "Hive 连接的执行地址已改变，已停止访问原数仓；请恢复连接配置或登记新的执行环境");
        return property;
    }

    DbQueryProperty computeProperty(Space space, String engine, Long computeDatasourceId) {
        if("HIVE_TEZ".equals(engine)) {
            require(computeDatasourceId==null || computeDatasourceId.equals(space.getDatasourceId()),"Hive/Tez 必须使用空间绑定的 HiveServer2 连接");
            return property(space);
        }
        require("KYUUBI_SPARK".equals(engine),"未知计算引擎");
        require(computeDatasourceId!=null && !computeDatasourceId.equals(space.getDatasourceId()),"请选择独立登记的 Kyuubi/Spark 连接");
        require(sourceExplicitlyAssigned(computeDatasourceId,space.getProjectId()),"当前项目未获授权使用该 Kyuubi 连接");
        DbQueryProperty selected=datasourceProperty(computeDatasourceId);
        require(!tech.qiantong.qdata.common.database.core.HiveEndpointIdentity.sharesDirectEndpoint(selected,property(space)),"Kyuubi/Spark 需要独立的服务地址");
        var query=factory.createDbQuery(selected);
        try(var connection=query.getConnection();var statement=connection.createStatement()) {
            statement.setQueryTimeout(60);
            try(var catalog=statement.executeQuery("SELECT current_catalog()")) {
                require(catalog.next() && "spark_catalog".equalsIgnoreCase(catalog.getString(1)),"所选连接不是已验证的 Kyuubi/Spark Catalog");
            }
            Set<String> databases=new HashSet<>();
            try(var rows=statement.executeQuery("SHOW DATABASES")) {while(rows.next())databases.add(rows.getString(1).toLowerCase(Locale.ROOT));}
            require(databases.containsAll(space.getDatabases().values()),"Kyuubi/Spark 未看到当前空间的全部五层 Hive 数据库");
        } catch(IllegalArgumentException error) {throw error;}
        catch(Exception error) {throw new IllegalArgumentException("Kyuubi/Spark 连接或五库可见性验证失败",error);}
        finally {query.close();}
        return selected;
    }

    public Space createSpace(Space space) {
        require(canManage(), "配置分层数据库绑定需要模型、连接及项目管理权限");
        require(space.getId() == null, "数仓空间绑定创建后不可原地修改，请新建空间保留历史引用");
        require(projectAccessible(space.getProjectId()), "无权在该项目创建数仓空间");
        require(space.getName() != null && !space.getName().isBlank() && space.getName().length() <= 128, "请填写数仓空间名称");
        require(space.getEnvironmentCode() != null && space.getEnvironmentCode().matches("[a-z][a-z0-9_]{0,31}"), "环境标识只能使用小写字母、数字及下划线");
        space.setDatabases(validateDatabases(space.getDatabases()));
        Engine engine = engines().stream().filter(e -> Objects.equals(e.getId(), space.getEngineId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请选择已授权的 Hive 执行环境"));
        require(sourceAssigned(engine.getDatasourceId(), space.getProjectId()), "Hive 连接尚未分配给该项目");
        space.setDatasourceId(engine.getDatasourceId());
        try {
            return transaction.execute(status -> {
                GeneratedKeyHolder key = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("INSERT INTO dp_warehouse_space(name,project_id,environment_code,engine_id,databases_json,create_by) VALUES (?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                    statement.setString(1, space.getName()); statement.setLong(2, space.getProjectId());
                    statement.setString(3, space.getEnvironmentCode()); statement.setLong(4, space.getEngineId());
                    statement.setString(5, JSON.toJSONString(space.getDatabases())); statement.setString(6, SecurityUtils.getUsername()); return statement;
                }, key);
                space.setId(key.getKey().longValue());
                for (var binding : space.getDatabases().entrySet())
                    jdbc.update("INSERT INTO dp_warehouse_namespace(engine_id,database_name,space_id,layer_code) VALUES (?,?,?,?)",
                            space.getEngineId(), binding.getValue(), space.getId(), binding.getKey());
                return getInternal(space.getId());
            });
        } catch (DuplicateKeyException error) {
            throw new IllegalArgumentException("该空间名称或目标数据库已被登记，请使用独立的空间和数据库", error);
        }
    }

    public List<Map<String,Object>> inspect(Long id) {
        authorize(id);
        Space space = getInternal(id);
        var query = factory.createDbQuery(property(space));
        Set<String> visible = new HashSet<>();
        try (var connection = query.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            try (var rows = statement.executeQuery("SHOW DATABASES")) {
                while (rows.next()) visible.add(rows.getString(1).toLowerCase(Locale.ROOT));
            }
        } catch (Exception error) { throw new IllegalArgumentException("Hive 数据库可见性检查失败，请核对连接和权限", error); }
        finally { query.close(); }
        return WarehouseSql.LAYERS.stream().map(layer -> Map.<String,Object>of("layer", layer,
                "database", space.getDatabases().get(layer),
                "status", visible.contains(space.getDatabases().get(layer)) ? "VISIBLE" : "NOT_VISIBLE",
                "message", visible.contains(space.getDatabases().get(layer)) ? "Hive 中可见" : "未创建或当前身份无权查看")).toList();
    }

    /** Called inside the model-save transaction after request authorization. */
    void registerMaterialization(WarehouseDefinition definition, Space space, String user) {
        if (space == null) return;
        if (definition.getDatasetId() == null) {
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO dp_warehouse_dataset(space_id,code,name,layer_code,create_by) VALUES (?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, space.getId()); statement.setString(2, definition.getTableName());
                statement.setString(3, definition.getName()); statement.setString(4, definition.getLayer());
                statement.setString(5, user); return statement;
            }, key);
            definition.setDatasetId(key.getKey().longValue());
        } else {
            List<String> datasetCodes=jdbc.queryForList("SELECT code FROM dp_warehouse_dataset WHERE id=? AND space_id=? AND layer_code=?",
                    String.class, definition.getDatasetId(), space.getId(), definition.getLayer());
            require(datasetCodes.size()==1,"数据集版本必须属于相同项目空间和分层");
            require(sameLogicalDataset(datasetCodes.get(0),definition.getTableName()),
                    "已有数据集只允许登记同名逻辑表或其版本表；新表请新建数据集");
        }
        List<Map<String,Object>> registered = jdbc.queryForList("SELECT dataset_id,database_name,table_name FROM dp_warehouse_materialization WHERE model_id=?", definition.getId());
        if (registered.isEmpty()) {
            jdbc.update("INSERT INTO dp_warehouse_materialization(model_id,dataset_id,engine_id,database_name,table_name) VALUES (?,?,?,?,?)",
                    definition.getId(), definition.getDatasetId(), space.getEngineId(), definition.getDatabaseName(), definition.getTableName());
        } else {
            var old = registered.get(0);
            require(((Number)old.get("dataset_id")).longValue() == definition.getDatasetId()
                    && Objects.equals(old.get("database_name"), definition.getDatabaseName())
                    && Objects.equals(old.get("table_name"), definition.getTableName()),
                    "已有物理版本的数据库、表名和数据集身份不可改变");
        }
    }
    static boolean sameLogicalDataset(String code,String table) {
        if(code==null || table==null)return false;
        String base=code.toLowerCase(Locale.ROOT),candidate=table.toLowerCase(Locale.ROOT);
        return candidate.equals(base) || candidate.startsWith(base+"__") && candidate.length()>base.length()+2;
    }
}
