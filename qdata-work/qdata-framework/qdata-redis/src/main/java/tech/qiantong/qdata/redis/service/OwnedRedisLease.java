package tech.qiantong.qdata.redis.service;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A renewable execution lease whose previous owner cannot release a replacement. */
public final class OwnedRedisLease implements AutoCloseable {
    private static final ScheduledExecutorService RENEWALS=Executors.newScheduledThreadPool(2, task->{
        Thread thread=new Thread(task,"qdata-execution-lease");thread.setDaemon(true);return thread;
    });
    private final IRedisService redis;
    private final String key,owner;
    private final long seconds;
    private final AtomicBoolean valid=new AtomicBoolean(true),closed=new AtomicBoolean(false);
    private final ScheduledFuture<?> renewal;

    private OwnedRedisLease(IRedisService redis,String key,String owner,long seconds) {
        this.redis=redis;this.key=key;this.owner=owner;this.seconds=seconds;
        renewal=RENEWALS.scheduleAtFixedRate(()->{
            if(closed.get() || !valid.get())return;
            try { if(!redis.renewLock(key,owner,seconds))valid.set(false); }
            catch(RuntimeException unavailable) { valid.set(false); }
        },seconds/3,seconds/3,TimeUnit.SECONDS);
    }
    public static OwnedRedisLease acquire(IRedisService redis,String key,long seconds) {
        if(seconds<3)throw new IllegalArgumentException("Lease duration must be at least three seconds");
        String owner=UUID.randomUUID().toString();
        if(!redis.tryAcquireLock(key,owner,seconds))throw new IllegalStateException("任务已有执行持有者，请查看当前实例");
        return new OwnedRedisLease(redis,key,owner,seconds);
    }
    public void requireOwner() {
        if(closed.get() || !valid.get())throw new IllegalStateException("任务执行租约已失效，不能确认成功");
        try {
            if(!redis.renewLock(key,owner,seconds)) {
                valid.set(false);throw new IllegalStateException("任务执行持有者已变化，不能确认成功");
            }
        } catch(RuntimeException unavailable) { valid.set(false);throw unavailable; }
    }
    public boolean isOwner() {
        try { requireOwner();return true; } catch(RuntimeException lost) { return false; }
    }
    @Override public void close() {
        if(!closed.compareAndSet(false,true))return;
        renewal.cancel(false);
        try { redis.releaseLock(key,owner); }
        catch(RuntimeException unavailable) {
            org.slf4j.LoggerFactory.getLogger(OwnedRedisLease.class).warn("Execution lease release deferred to expiry for {}",key);
        }
    }
}
