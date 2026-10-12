package tech.qiantong.qdata.module.dp.warehouse;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.module.dp.api.warehouse.WarehouseBatchExecution;
import tech.qiantong.qdata.redis.service.IRedisService;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import static tech.qiantong.qdata.module.dp.warehouse.WarehouseSql.require;

/** Each step owns a new table; uncertain output is retained and never retried in place. */
@Service
@RequiredArgsConstructor
public class WarehouseBatchExecutor implements WarehouseBatchExecution {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final WarehouseBatchRegistry batches;
    private final WarehouseService models;
    private final DataSourceFactory factory;
    private final IRedisService redis;

    @Override public String batchForTask(long taskId) {
        var rows=jdbc.queryForList("SELECT id FROM dp_warehouse_batch WHERE task_id=?",String.class,taskId);return rows.isEmpty()?null:rows.get(0);
    }
    @Override public void authorizeTask(long taskId) {String id=batchForTask(taskId);require(id!=null,"受管任务的批次绑定缺失");batches.get(id);}
    @Override public void failToStart(long taskId,String message) {
        String id=batchForTask(taskId);if(id==null)return;
        transaction.executeWithoutResult(status->{
            int updated=jdbc.update("UPDATE dp_warehouse_batch SET status='FAILED',finished_at=NOW(6),message=? WHERE id=? AND status IN ('READY','RUNNING') AND active_instance_id IS NULL",message,id);
            if(updated==1)jdbc.update("UPDATE dp_warehouse_batch_step SET status='BLOCKED',message='开发任务启动失败，未执行Hive',finished_at=NOW(6) WHERE batch_id=? AND status='QUEUED'",id);
        });
    }
    private void lease(long taskId,String owner) {
        require(owner!=null && redis.renewLock("dpp:data-development:run:"+taskId,owner,120),"开发任务运行锁已失效；当前输出保留，禁止发布或重复写入");
    }
    private void instance(Map<String,Object> batch,long taskId,long instanceId) {
        require(batch.get("task_id")!=null && ((Number)batch.get("task_id")).longValue()==taskId,"开发任务与数仓批次不匹配");
        require(jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task_instance WHERE id=? AND task_id=? AND task_version=? AND del_flag='0'",Long.class,instanceId,taskId,batch.get("task_version"))==1,"开发任务实例与固定版本不匹配");
    }
    @Override public StepResult execute(String id,int ordinal,long taskId,long instanceId,String nodeCode,String owner) {
        lease(taskId,owner);var batch=batches.internal(id);instance(batch,taskId,instanceId);
        var rows=jdbc.queryForList("SELECT * FROM dp_warehouse_batch_step WHERE batch_id=? AND ordinal=?",id,ordinal);require(rows.size()==1,"批次节点不存在");var step=rows.get(0);
        require(Objects.equals(step.get("node_code"),nodeCode),"开发节点与固定模型快照不匹配");
        if("SUCCESS".equals(batch.get("status"))) {
            require("SUCCESS".equals(step.get("status")),"成功批次的节点状态不完整");
            return new StepResult(((Number)step.get("row_count")).longValue(),true,"复用既有成功版本，未再次写入Hive");
        }
        require(Set.of("READY","RUNNING").contains(String.valueOf(batch.get("status"))),"批次未就绪；失败或未知输出只能创建新尝试");
        require("QUEUED".equals(step.get("status")),"节点已有执行尝试，禁止向同一目标重试");
        var dependencies=JSON.parseArray(String.valueOf(step.get("dependencies_json")),Integer.class);
        for(int parent:dependencies) {
            String parentStatus=jdbc.queryForObject("SELECT status FROM dp_warehouse_batch_step WHERE batch_id=? AND ordinal=?",String.class,id,parent);
            if(Set.of("FAILED","UNKNOWN","BLOCKED").contains(Objects.toString(parentStatus,""))) {
                lease(taskId,owner);
                String message="上游节点 "+parent+" 状态为 "+parentStatus+"，未执行当前目标；其他独立支线继续";
                require(jdbc.update("UPDATE dp_warehouse_batch_step SET status='BLOCKED',message=?,finished_at=NOW(6) WHERE batch_id=? AND ordinal=? AND status='QUEUED' AND EXISTS (SELECT 1 FROM dp_warehouse_batch b WHERE b.id=? AND b.status='RUNNING' AND b.active_instance_id=? AND b.lease_owner=?)",message,id,ordinal,id,instanceId,owner)==1,"未能可靠登记依赖阻断状态，停止批次");
                throw new DependencyBlocked(message);
            }
            require("SUCCESS".equals(parentStatus),"上游版本尚未成功，未执行当前节点");
        }
        var definition=JSON.parseObject(String.valueOf(step.get("definition_json")),WarehouseDefinition.class);
        transaction.executeWithoutResult(status->{
            jdbc.update("UPDATE dp_warehouse_batch SET status='RUNNING',active_instance_id=?,lease_owner=?,started_at=COALESCE(started_at,NOW(6)) WHERE id=? AND status IN ('READY','RUNNING') AND (active_instance_id IS NULL OR active_instance_id=?)",instanceId,owner,id,instanceId);
            require(jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_batch WHERE id=? AND status='RUNNING' AND active_instance_id=? AND lease_owner=?",Long.class,id,instanceId,owner)==1,"其他尝试已持有该批次，禁止重复执行");
            require(jdbc.update("UPDATE dp_warehouse_batch_step SET status='RUNNING',instance_id=?,started_at=NOW(6) WHERE batch_id=? AND ordinal=? AND status='QUEUED'",instanceId,id,ordinal)==1,"当前节点已被其他执行认领");
            jdbc.update("INSERT INTO dp_warehouse_run(batch_id,model_id,revision,mode,status,definition_json,create_by,started_at) VALUES (?,?,?,'MANAGED','RUNNING',?,?,NOW(6))",id,definition.getId(),definition.getRevision(),String.valueOf(step.get("definition_json")),batch.get("create_by"));
        });
        boolean[] hiveWriteRequested={false};
        try {
            var current=models.getInternal(definition.getId());
            require(JSON.toJSONString(current).equals(JSON.toJSONString(definition)),"受管模型定义已发生变化，停止执行");
            var compute=batches.computeProperty(batch,definition);
            if("ODS".equals(definition.getLayer()))snapshotInput(id,definition,batch,taskId,owner,compute,()->hiveWriteRequested[0]=true);
            else {hiveWriteRequested[0]=true;models.execute(definition,"LOAD",id,compute);}
            lease(taskId,owner);
            var query=factory.createDbQuery(compute);WarehouseDataFingerprint.Value fingerprint;
            try(Connection connection=query.getConnection()) {
                fingerprint=WarehouseDataFingerprint.read(connection,List.of(WarehouseSql.canonical(definition.getTableName(),definition.getDatabaseName())),WarehouseSql.ordered(definition),null,null,1_000_000);
            } finally {query.close();}
            lease(taskId,owner);
            require(jdbc.update("UPDATE dp_warehouse_batch_step SET status='SUCCESS',row_count=?,output_fingerprint=?,finished_at=NOW(6),message='固定版本加工与结果摘要校验完成' WHERE batch_id=? AND ordinal=? AND status='RUNNING' AND instance_id=? AND EXISTS (SELECT 1 FROM dp_warehouse_batch b WHERE b.id=? AND b.status='RUNNING' AND b.active_instance_id=? AND b.lease_owner=?)",fingerprint.rows(),fingerprint.sha256(),id,ordinal,instanceId,id,instanceId,owner)==1,"输出已保留，但节点结果未能可靠登记");
            return new StepResult(fingerprint.rows(),false,"新版本已完成；旧表与失败尝试保持");
        } catch(Exception error) {
            String outcome=hiveWriteRequested[0] && !(error instanceof WarehouseOutputRejected)
                    && !(error instanceof WarehouseInputRejected)?"UNKNOWN":"FAILED";
            String message=(error instanceof WarehouseInputRejected?"写入前数值预检失败；目标数据未写入。":
                    error instanceof WarehouseOutputRejected?"输出约束校验失败；数据保留，禁止原地重试。":
                    hiveWriteRequested[0]?"Hive请求已提交但结果未可靠登记；输出保留，禁止原地重试。":"未提交Hive写入请求；目标数据未写入。")+error.getMessage();
            boolean recorded=false;
            try {
                recorded=Boolean.TRUE.equals(transaction.execute(status->{
                    int changed=jdbc.update("UPDATE dp_warehouse_batch_step SET status=?,message=?,finished_at=NOW(6) WHERE batch_id=? AND ordinal=? AND instance_id=? AND status='RUNNING'",outcome,message,id,ordinal,instanceId);
                    if(changed==1)jdbc.update("UPDATE dp_warehouse_run SET status=?,message=?,finished_at=NOW(6) WHERE batch_id=? AND model_id=?",outcome,message,id,definition.getId());
                    return changed==1;
                }));
            } catch(Exception persistenceFailure) { error.addSuppressed(persistenceFailure); }
            // A metadata outage is a workflow-wide stop, even if recording UNKNOWN later succeeds.
            if(recorded && !(error instanceof org.springframework.dao.DataAccessException)) {
                lease(taskId,owner);
                require(jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_batch WHERE id=? AND status='RUNNING' AND active_instance_id=? AND lease_owner=?",Long.class,id,instanceId,owner)==1,"批次持有者已变化，停止后续支线");
                throw new StepFailed(message,error);
            }
            throw error instanceof RuntimeException?(RuntimeException)error:new IllegalStateException(error.getMessage(),error);
        }
    }

    private void snapshotInput(String id,WarehouseDefinition target,Map<String,Object> batch,long taskId,String owner,tech.qiantong.qdata.common.database.constants.DbQueryProperty compute,Runnable beforeHiveWrite) throws Exception {
        var proof=WarehouseBatchRegistry.parseInputs(batch).stream().filter(p->p.datasetId()==target.getDatasetId()).findFirst().orElseThrow(()->new IllegalArgumentException("缺少固定原始输入清单"));
        var query=factory.createDbQuery(compute);var columns=WarehouseSql.ordered(target);
        List<String> sources=proof.readModels().stream().map(m->WarehouseSql.canonical(m.getTableName(),m.getDatabaseName())).toList();
        try(Connection connection=query.getConnection();Statement statement=connection.createStatement()) {
            statement.setQueryTimeout(1800);
            for(var source:proof.readModels())models.verifySchema(statement,source,WarehouseSql.qualified(source.getDatabaseName(),source.getTableName()));
            var before=WarehouseDataFingerprint.read(connection,sources,columns,null,null,1_000_000);
            require(before.rows()==proof.rows() && before.sha256().equals(proof.sha256()),"原始输入已变化，不能混入当前固定批次");
            lease(taskId,owner);beforeHiveWrite.run();models.execute(target,"DDL",id,compute);lease(taskId,owner);
            String table=WarehouseSql.qualified(target.getDatabaseName(),target.getTableName());
            try(ResultSet count=statement.executeQuery("SELECT COUNT(*) FROM "+table)){count.next();require(count.getLong(1)==0,"快照目标不是空表，禁止追加或覆盖");}
            String fields=columns.stream().map(c->WarehouseSql.id(c.getName())).collect(Collectors.joining(","));
            String partitions=columns.stream().filter(WarehouseDefinition.Column::isPartition).map(c->WarehouseSql.id(c.getName())).collect(Collectors.joining(","));
            if(!partitions.isEmpty()){statement.execute("SET hive.exec.dynamic.partition=true");statement.execute("SET hive.exec.dynamic.partition.mode=nonstrict");}
            String sql="INSERT INTO TABLE "+table+(partitions.isEmpty()?"":" PARTITION ("+partitions+")")+" "+sources.stream().map(source->{String[] parts=source.split("\\.");return "SELECT "+fields+" FROM "+WarehouseSql.qualified(parts[0],parts[1]);}).collect(Collectors.joining(" UNION ALL "));
            jdbc.update("UPDATE dp_warehouse_run SET status='RUNNING',sql_text=CONCAT(COALESCE(sql_text,''),?),message='原始记录版本快照保存中' WHERE batch_id=? AND model_id=?",sql+";\n",id,target.getId());
            statement.execute(sql);lease(taskId,owner);
            var after=WarehouseDataFingerprint.read(connection,sources,columns,null,null,1_000_000);
            var output=WarehouseDataFingerprint.read(connection,List.of(WarehouseSql.canonical(target.getTableName(),target.getDatabaseName())),columns,null,null,1_000_000);
            require(before.equals(after) && before.equals(output),"原始快照对账失败；现有输出保留，未允许下游使用");
            models.finish(id,target,output.rows(),"原始记录快照完成，来源/字段/重复次数一致");
        } finally {query.close();}
    }
    @Override public void fail(String id,long taskId,long instanceId,String message) {
        var batch=batches.internal(id);instance(batch,taskId,instanceId);
        transaction.executeWithoutResult(status->{
            int changed=jdbc.update("UPDATE dp_warehouse_batch SET status=CASE WHEN status='UNKNOWN' OR EXISTS (SELECT 1 FROM dp_warehouse_batch_step s WHERE s.batch_id=? AND s.status IN ('RUNNING','UNKNOWN')) THEN 'UNKNOWN' ELSE 'FAILED' END,finished_at=COALESCE(finished_at,NOW(6)),message=? WHERE id=? AND status<>'SUCCESS' AND (active_instance_id IS NULL OR active_instance_id=?)",id,message,id,instanceId);
            if(changed==1)jdbc.update("UPDATE dp_warehouse_batch_step SET status='BLOCKED',message='上游失败或结果未知，未写入该目标',finished_at=NOW(6) WHERE batch_id=? AND status='QUEUED'",id);
        });
    }
    @Override public void complete(String id,long taskId,long instanceId,String owner) {
        lease(taskId,owner);var batch=batches.internal(id);instance(batch,taskId,instanceId);
        if("SUCCESS".equals(batch.get("status")))return;
        transaction.executeWithoutResult(status->{
            require(jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_batch_step WHERE batch_id=? AND status<>'SUCCESS'",Long.class,id)==0,"仍有未成功节点，不能完成批次");
            require(jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task_instance WHERE id=? AND task_id=? AND status='7'",Long.class,instanceId,taskId)==1,"开发任务最终成功状态尚未可靠保存");
            require(jdbc.update("UPDATE dp_warehouse_batch SET status='SUCCESS',finished_at=NOW(6),message='固定版本计算完成；质量与服务发布另行验收' WHERE id=? AND status='RUNNING' AND active_instance_id=? AND lease_owner=?",id,instanceId,owner)==1,"批次运行身份已失效，不能发布当前结果");
        });
    }
    public void reconcile(long spaceId) {
        var rows=jdbc.queryForList("SELECT id,task_id,lease_owner,active_instance_id FROM dp_warehouse_batch WHERE space_id=? AND status='RUNNING' AND active_instance_id IS NOT NULL",spaceId);
        for(var row:rows) {
            String actual;
            try{actual=redis.get("dpp:data-development:run:"+row.get("task_id"));}catch(Exception unavailable){continue;}
            if(actual!=null && Objects.equals(actual,row.get("lease_owner")))continue;
            String id=String.valueOf(row.get("id"));
            transaction.executeWithoutResult(status->{
                int changed=jdbc.update("UPDATE dp_warehouse_batch SET status='UNKNOWN',finished_at=NOW(6),message='运行租约已失效，Hive请求可能仍在运行；请核对保留输出并创建新尝试' WHERE id=? AND status='RUNNING' AND lease_owner <=> ? AND active_instance_id=?",id,row.get("lease_owner"),row.get("active_instance_id"));
                if(changed==0)return;
                jdbc.update("UPDATE dp_warehouse_batch_step SET status='UNKNOWN',finished_at=NOW(6),message='执行结果尚未确认，禁止原地重试' WHERE batch_id=? AND status='RUNNING'",id);
                jdbc.update("UPDATE dp_warehouse_batch_step SET status='BLOCKED',finished_at=NOW(6),message='上游结果未知，未执行该节点' WHERE batch_id=? AND status='QUEUED'",id);
                jdbc.update("UPDATE dp_warehouse_run SET status='UNKNOWN',finished_at=NOW(6),message='运行持有者失联，Hive输出保留，禁止原地重试' WHERE batch_id=? AND mode='MANAGED' AND status IN ('RUNNING','QUEUED')",id);
                jdbc.update("UPDATE dpp_etl_task_instance SET status='6',end_time=COALESCE(end_time,NOW()),remark='运行持有者失联；Hive结果未知，请从数仓批次创建新尝试' WHERE id=? AND task_id=? AND status IN ('0','1','2','4','12','14','15','17')",row.get("active_instance_id"),row.get("task_id"));
                jdbc.update("UPDATE dpp_etl_node_instance SET status='6',end_time=COALESCE(end_time,NOW()) WHERE task_instance_id=? AND status IN ('0','1','12','17')",row.get("active_instance_id"));
            });
        }
    }
}
