package tech.qiantong.qdata.quality.service.quality;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.core.HiveEndpointIdentity;
import tech.qiantong.qdata.common.database.core.HiveTableIdentity;
import java.util.*;

/** Recheck durable project/connection authorization for Quartz and worker admission, without a browser session. */
@Service
@RequiredArgsConstructor
public class QualityExecutionProjectGuard {
    private final JdbcTemplate jdbc;
    public Long validate(QualityExecutionSnapshots.Plan plan){
        var owners=jdbc.queryForList("SELECT t.project_id FROM dpp_quality_task t JOIN att_project p ON p.id=t.project_id WHERE t.id=? AND t.del_flag='0' AND t.status='0' AND p.del_flag='0' AND p.valid_flag='1'",Long.class,plan.taskId());
        if(owners.size()!=1)throw new IllegalArgumentException("质量任务未启用、项目已停用或尚未确认项目归属");
        Long project=owners.get(0),fixed=JSON.parseObject(plan.json()).getLong("projectId");
        if(fixed!=null && !fixed.equals(project))throw new IllegalArgumentException("质量快照与任务项目不一致");
        Map<Long,Map<String,Object>> sources=new HashMap<>();
        for(var object:plan.objects()){
            Long id=object.getDatasourceId();
            var source=sources.computeIfAbsent(id,key->{var rows=jdbc.queryForList("SELECT d.datasource_type,d.ip,d.port,d.datasource_config FROM da_datasource d WHERE d.id=? AND d.del_flag='0' AND d.valid_flag='1' AND EXISTS(SELECT 1 FROM da_datasource_project_rel r WHERE r.datasource_id=d.id AND r.project_id=? AND r.valid_flag='1')",key,project);if(rows.size()!=1)throw new IllegalArgumentException("质量检查连接已停用或未授权给任务项目");return rows.get(0);});
            if(!"Hive".equalsIgnoreCase(String.valueOf(source.get("datasource_type"))))continue;
            var property=new DbQueryProperty(String.valueOf(source.get("datasource_type")),String.valueOf(source.get("ip")),((Number)source.get("port")).longValue(),String.valueOf(source.get("datasource_config")));
            String database=HiveTableIdentity.resolve(object.getTableName(),property.getDbName()).databaseName();
            var projects=jdbc.queryForList("SELECT DISTINCT s.project_id FROM dp_warehouse_namespace n JOIN dp_warehouse_engine e ON e.id=n.engine_id JOIN dp_warehouse_space s ON s.id=n.space_id WHERE n.database_name=? AND e.connection_fingerprint=?",Long.class,database,HiveEndpointIdentity.fingerprint(property));
            if(!projects.isEmpty()){
                if(projects.size()!=1 || !projects.contains(project))throw new IllegalArgumentException("质量检查对象属于另一项目的Hive命名空间");
            }else if(jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_namespace WHERE database_name=?",Long.class,database)>0 || !database.equals(HiveTableIdentity.identifier(property.getDbName())))throw new IllegalArgumentException("质量检查Hive数据库未登记到当前连接和项目");
        }
        return project;
    }
}
