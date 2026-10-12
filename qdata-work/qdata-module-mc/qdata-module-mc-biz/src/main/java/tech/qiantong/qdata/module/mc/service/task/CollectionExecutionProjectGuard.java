package tech.qiantong.qdata.module.mc.service.task;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.core.HiveEndpointIdentity;
import tech.qiantong.qdata.common.database.core.HiveTableIdentity;
import java.util.*;

/** Durable admission checks for background collectors; no browser identity is synthesized. */
@Service
@RequiredArgsConstructor
public class CollectionExecutionProjectGuard {
    private final JdbcTemplate jdbc;
    public Long validate(Long taskId){
        var rows=jdbc.queryForList("SELECT t.project_id,t.datasource_id,t.collection_scope,t.table_allowlist FROM mc_task t JOIN att_project p ON p.id=t.project_id WHERE t.id=? AND t.del_flag='0' AND t.status='1' AND p.del_flag='0' AND p.valid_flag='1'",taskId);
        if(rows.size()!=1)throw new IllegalArgumentException("采集任务未发布、项目已停用或尚未确认项目归属");
        var task=rows.get(0);Long project=CollectionProjectAccess.number(task.get("project_id")),sourceId=CollectionProjectAccess.number(task.get("datasource_id"));
        var sources=jdbc.queryForList("SELECT d.datasource_type,d.ip,d.port,d.datasource_config FROM da_datasource d WHERE d.id=? AND d.del_flag='0' AND d.valid_flag='1' AND EXISTS(SELECT 1 FROM da_datasource_project_rel r WHERE r.datasource_id=d.id AND r.project_id=? AND r.valid_flag='1')",sourceId,project);
        if(sources.size()!=1)throw new IllegalArgumentException("采集连接已停用或未授权给任务项目");
        var source=sources.get(0);if(!"Hive".equalsIgnoreCase(String.valueOf(source.get("datasource_type"))))return project;
        var databases=jdbc.queryForList("SELECT db_name FROM mc_task_scope WHERE task_id=? AND del_flag='0'",String.class,taskId);
        if(!"1".equals(String.valueOf(task.get("collection_scope"))) || databases.isEmpty())throw new IllegalArgumentException("Hive采集必须固定数据库范围");
        var property=new DbQueryProperty(String.valueOf(source.get("datasource_type")),String.valueOf(source.get("ip")),((Number)source.get("port")).longValue(),String.valueOf(source.get("datasource_config")));
        Set<String> normalized=new HashSet<>();
        for(String name:databases){
            String database=HiveTableIdentity.identifier(name);normalized.add(database);
            var owners=jdbc.queryForList("SELECT DISTINCT s.project_id FROM dp_warehouse_namespace n JOIN dp_warehouse_engine e ON e.id=n.engine_id JOIN dp_warehouse_space s ON s.id=n.space_id WHERE n.database_name=? AND e.connection_fingerprint=?",Long.class,database,HiveEndpointIdentity.fingerprint(property));
            if(!owners.isEmpty()){
                if(owners.size()!=1 || !owners.contains(project))throw new IllegalArgumentException("采集范围属于另一项目的Hive命名空间");
            }else if(jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_namespace WHERE database_name=?",Long.class,database)>0 || !database.equals(HiveTableIdentity.identifier(property.getDbName())))throw new IllegalArgumentException("采集Hive数据库未登记到当前连接和项目");
        }
        for(String name:Objects.toString(task.get("table_allowlist"),"").split("[\\r\\n,]+"))if(!name.isBlank() && !normalized.contains(HiveTableIdentity.resolve(name.trim(),property.getDbName()).databaseName()))throw new IllegalArgumentException("指定Hive表超出任务固定数据库范围");
        return project;
    }
}
