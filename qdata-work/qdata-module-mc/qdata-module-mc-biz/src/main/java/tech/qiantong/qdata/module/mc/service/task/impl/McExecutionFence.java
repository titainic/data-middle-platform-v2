package tech.qiantong.qdata.module.mc.service.task.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import tech.qiantong.qdata.redis.service.OwnedRedisLease;

/** A task-row fence is held through each metadata transaction, including nested operations. */
final class McExecutionFence {
    private record Run(long taskId,long instanceId,OwnedRedisLease lease) { }
    private static final ThreadLocal<Run> CURRENT=new ThreadLocal<>();
    static void open(long taskId,long instanceId,OwnedRedisLease lease) {
        if(CURRENT.get()!=null)throw new IllegalStateException("Nested metadata execution is not supported");
        CURRENT.set(new Run(taskId,instanceId,lease));
    }
    static void verify(JdbcTemplate jdbc) {
        Run run=CURRENT.get();if(run==null)return;
        run.lease.requireOwner();
        Long current=jdbc.queryForObject("SELECT active_instance_id FROM mc_task WHERE id=? FOR UPDATE",Long.class,run.taskId);
        if(current==null || current.longValue()!=run.instanceId)throw new IllegalStateException("元数据采集持有者已变化，停止写入旧尝试");
    }
    static void verifyOwner() {Run run=CURRENT.get();if(run!=null)run.lease.requireOwner();}
    static void close(){CURRENT.remove();}
}
