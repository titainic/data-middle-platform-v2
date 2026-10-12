package tech.qiantong.qdata.module.da.service.datasource;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.core.DbTable;
import tech.qiantong.qdata.common.database.core.HiveTableIdentity;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import java.util.*;

/** Project-authorized Hive database choices for the integration editor. */
@Service
@RequiredArgsConstructor
public class HiveNamespaceBrowser {
    private final JdbcTemplate jdbc;
    private final IDaDatasourceService datasources;
    private final tech.qiantong.qdata.common.database.DataSourceFactory factory;

    DbQueryProperty property(Long sourceId) {
        var source=datasources.getDaDatasourceById(sourceId);
        if(source==null || !"HIVE".equalsIgnoreCase(source.getDatasourceType()))
            throw new IllegalArgumentException("请选择有效的 Hive 数据源");
        return new DbQueryProperty(source.getDatasourceType(),source.getIp(),source.getPort(),source.getDatasourceConfig());
    }

    Set<String> allowed(Long sourceId, String defaultDatabase) {
        boolean admin=SecurityUtils.isGlobalAdministrator();
        List<Long> projects;
        if(admin) projects=jdbc.queryForList("SELECT id FROM att_project WHERE del_flag='0' AND valid_flag='1'",Long.class);
        else projects=jdbc.queryForList("SELECT p.id FROM att_project p JOIN da_datasource_project_rel d ON d.project_id=p.id AND d.datasource_id=? AND d.valid_flag='1' WHERE p.del_flag='0' AND p.valid_flag='1' AND (p.manager_id=? OR EXISTS (SELECT 1 FROM att_project_user_rel u WHERE u.project_id=p.id AND u.user_id=? AND u.del_flag='0' AND u.valid_flag='1'))",Long.class,sourceId,SecurityUtils.getUserId(),SecurityUtils.getUserId());
        if(projects.isEmpty())throw new IllegalArgumentException("当前用户没有该 Hive 连接的项目权限");
        String endpoint=tech.qiantong.qdata.common.database.core.HiveEndpointIdentity.fingerprint(property(sourceId));
        Set<String> databases=new LinkedHashSet<>();
        String defaultName=HiveTableIdentity.identifier(defaultDatabase);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_namespace WHERE database_name=?",Long.class,defaultName)==0)
            databases.add(defaultName);
        List<Object> args=new ArrayList<>();args.add(endpoint);args.addAll(projects);
        databases.addAll(jdbc.queryForList("SELECT n.database_name FROM dp_warehouse_namespace n JOIN dp_warehouse_engine e ON e.id=n.engine_id JOIN dp_warehouse_space s ON s.id=n.space_id WHERE e.connection_fingerprint=? AND s.project_id IN ("+String.join(",",Collections.nCopies(projects.size(),"?"))+") ORDER BY s.id,FIELD(n.layer_code,'ODS','DWD','DWS','DWT','ADS')",String.class,args.toArray()));
        // A successful Spark batch proves this separate Kyuubi connection was validated for the space.
        // The project list above still requires both datasource assignment and user membership.
        List<Object> sparkArgs=new ArrayList<>(projects);sparkArgs.add(sourceId);sparkArgs.add(endpoint);
        databases.addAll(jdbc.queryForList("SELECT DISTINCT n.database_name FROM dp_warehouse_namespace n JOIN dp_warehouse_space s ON s.id=n.space_id WHERE s.project_id IN ("+String.join(",",Collections.nCopies(projects.size(),"?"))+") AND EXISTS (SELECT 1 FROM dp_warehouse_batch b WHERE b.space_id=s.id AND b.status='SUCCESS' AND JSON_CONTAINS(b.request_json,JSON_OBJECT('computeEngine','KYUUBI_SPARK','computeDatasourceId',?,'computeEndpointFingerprint',?)))",String.class,sparkArgs.toArray()));
        return databases;
    }

    public Set<Long> owningProjects(Long sourceId,String tableName) {
        var source=datasources.getDaDatasourceById(sourceId);
        if(source==null || !"HIVE".equalsIgnoreCase(source.getDatasourceType()))return Set.of();
        var property=property(sourceId);String database=HiveTableIdentity.resolve(tableName,property.getDbName()).databaseName();
        String endpoint=tech.qiantong.qdata.common.database.core.HiveEndpointIdentity.fingerprint(property);
        Set<Long> owners=new HashSet<>(jdbc.queryForList("SELECT DISTINCT s.project_id FROM dp_warehouse_namespace n JOIN dp_warehouse_engine e ON e.id=n.engine_id JOIN dp_warehouse_space s ON s.id=n.space_id WHERE e.connection_fingerprint=? AND n.database_name=?",Long.class,endpoint,database));
        owners.addAll(jdbc.queryForList("SELECT DISTINCT s.project_id FROM dp_warehouse_namespace n JOIN dp_warehouse_space s ON s.id=n.space_id JOIN da_datasource_project_rel r ON r.project_id=s.project_id AND r.datasource_id=? AND r.valid_flag='1' WHERE n.database_name=? AND EXISTS (SELECT 1 FROM dp_warehouse_batch b WHERE b.space_id=s.id AND b.status='SUCCESS' AND JSON_CONTAINS(b.request_json,JSON_OBJECT('computeEngine','KYUUBI_SPARK','computeDatasourceId',?,'computeEndpointFingerprint',?)))",Long.class,sourceId,database,sourceId,endpoint));
        return owners;
    }

    public void authorizeTable(Long sourceId,String tableName) {
        var source=datasources.getDaDatasourceById(sourceId);
        if(source==null || !"HIVE".equalsIgnoreCase(source.getDatasourceType()))return;
        var property=property(sourceId);
        String database=HiveTableIdentity.resolve(tableName,property.getDbName()).databaseName();
        if(!allowed(sourceId,property.getDbName()).contains(database))throw new IllegalArgumentException("无权访问该 Hive 数据库");
    }

    public void authorizeQuery(Long sourceId,String sql) {
        var source=datasources.getDaDatasourceById(sourceId);
        if(source==null || !"HIVE".equalsIgnoreCase(source.getDatasourceType()))return;
        var property=property(sourceId);Set<String> databases=allowed(sourceId,property.getDbName());
        var tables=tech.qiantong.qdata.common.database.core.HiveReadQuery.tables(sql,property.getDbName());
        for(String table:tables) if(!databases.contains(HiveTableIdentity.resolve(table,property.getDbName()).databaseName()))
            throw new IllegalArgumentException("查询引用了未授权的Hive数据库");
    }

    public List<String> databases(Long sourceId) {
        var property=property(sourceId);Set<String> allowed=allowed(sourceId,property.getDbName());
        var query=factory.createDbQuery(property);
        try {
            Set<String> visible=new HashSet<>();
            query.queryList("SHOW DATABASES").forEach(row->row.values().forEach(value->{if(value!=null)visible.add(value.toString().toLowerCase(Locale.ROOT));}));
            return allowed.stream().filter(visible::contains).toList();
        } finally {query.close();}
    }

    public List<DbTable> tables(Long sourceId,String database) {
        var property=property(sourceId);String name=HiveTableIdentity.identifier(database);
        if(!allowed(sourceId,property.getDbName()).contains(name))throw new IllegalArgumentException("数据库未绑定到当前用户有权访问的数仓空间");
        property.setDbName(name);var query=factory.createDbQuery(property);
        try {
            var tables=query.getTables(property);
            tables.forEach(table->table.setTableName(HiveTableIdentity.resolve(table.getTableName(),name).canonical()));
            return tables;
        } finally {query.close();}
    }
}
