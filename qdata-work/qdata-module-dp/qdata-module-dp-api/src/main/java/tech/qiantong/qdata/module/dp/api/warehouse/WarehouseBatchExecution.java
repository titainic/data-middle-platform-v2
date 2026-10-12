package tech.qiantong.qdata.module.dp.api.warehouse;

/** Invoked by the existing leased JDBC task runner; batch ownership is checked server-side. */
public interface WarehouseBatchExecution {
    String batchForTask(long taskId);
    void authorizeTask(long taskId);
    void failToStart(long taskId,String message);
    record StepResult(long rows,boolean reused,String message) { }
    /** The failed outcome was durably recorded while this worker still owns the batch. */
    class StepFailed extends IllegalStateException {
        public StepFailed(String message,Throwable cause){super(message,cause);}
    }
    /** A terminal upstream outcome was recorded; this node submitted no Hive work. */
    class DependencyBlocked extends IllegalStateException {
        public DependencyBlocked(String message){super(message);}
    }
    StepResult execute(String batchId,int ordinal,long taskId,long instanceId,String nodeCode,String leaseOwner);
    void fail(String batchId,long taskId,long instanceId,String message);
    void complete(String batchId,long taskId,long instanceId,String leaseOwner);
}
