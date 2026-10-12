package tech.qiantong.qdata.common.httpClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.function.LongSupplier;
import tech.qiantong.qdata.common.exception.DependencyUnavailableException;

/** Bounds database work per process and lets one request test a failed dependency after cooldown. */
public class DependencyGuard {
    private final int totalLimit, sourceLimit, failureLimit;
    private final long cooldown;
    private final LongSupplier clock;
    private final Map<Object, State> sources = new HashMap<>();
    private int active, waiting;
    private final int maxWaiting;
    private final long waitNanos;
    private static final class State {
        int active, failures;
        long generation, retryAt;
        boolean open, probing;
        final ArrayDeque<Object> waiters=new ArrayDeque<>();
    }
    public DependencyGuard() { this(32, 16, 5, Duration.ofSeconds(30), System::nanoTime, 8, Duration.ofMillis(250)); }
    public DependencyGuard(int totalLimit, int sourceLimit, int failureLimit, Duration cooldown, LongSupplier clock) {
        this(totalLimit,sourceLimit,failureLimit,cooldown,clock,0,Duration.ZERO);
    }
    public DependencyGuard(int totalLimit,int sourceLimit,int failureLimit,Duration cooldown,LongSupplier clock,int maxWaiting,Duration wait) {
        if (totalLimit < 1 || sourceLimit < 1 || failureLimit < 1 || cooldown.isNegative() || cooldown.isZero() || maxWaiting<0 || wait.isNegative())
            throw new IllegalArgumentException("Invalid dependency limits");
        this.totalLimit=totalLimit; this.sourceLimit=sourceLimit; this.failureLimit=failureLimit;
        this.cooldown=cooldown.toNanos(); this.clock=clock; this.maxWaiting=maxWaiting;this.waitNanos=wait.toNanos();
    }
    public synchronized Permit acquire(Object sourceId) {
        State state=sources.computeIfAbsent(sourceId, ignored -> new State());
        available(state);
        if (active<totalLimit && state.active<sourceLimit && state.waiters.isEmpty())return admit(state);
        if (maxWaiting==0 || waitNanos==0 || waiting>=maxWaiting)throw busy();
        Object ticket=new Object();state.waiters.addLast(ticket);waiting++;
        long deadline=System.nanoTime()+waitNanos;
        try {
            while(true) {
                available(state);
                long remaining=deadline-System.nanoTime();if(remaining<=0)throw busy();
                if(state.waiters.peekFirst()==ticket && active<totalLimit && state.active<sourceLimit)return admit(state);
                try {TimeUnit.NANOSECONDS.timedWait(this,remaining);}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new DependencyUnavailableException("服务请求等待已中断，请稍后重试");}
            }
        } finally {state.waiters.remove(ticket);waiting--;notifyAll();}
    }
    private void available(State state) {
        if (state.open && (clock.getAsLong()-state.retryAt<0 || state.probing))
            throw new DependencyUnavailableException("服务依赖暂时不可用，正在等待恢复，请稍后重试");
    }
    private DependencyUnavailableException busy(){return new DependencyUnavailableException("服务查询繁忙，请稍后重试");}
    private Permit admit(State state) {
        active++;state.active++;if(state.open)state.probing=true;
        return new Permit(state,state.generation);
    }
    public final class Permit implements AutoCloseable {
        private final State state;
        private final long generation;
        private boolean completed, closed;
        Permit(State state,long generation) { this.state=state; this.generation=generation; }
        public void succeeded() { complete(true); }
        public void failed() { complete(false); }
        private void complete(boolean success) {
            synchronized (DependencyGuard.this) {
                if (closed || completed) return;
                completed=true;
                // A request started before the circuit opened cannot undo the newer failure state.
                if (generation!=state.generation) return;
                if (success) { state.failures=0; state.open=false; state.probing=false; }
                else if (state.open || ++state.failures>=failureLimit) {
                    state.open=true; state.probing=false; state.retryAt=clock.getAsLong()+cooldown; state.generation++;
                }
                DependencyGuard.this.notifyAll();
            }
        }
        @Override public void close() {
            synchronized (DependencyGuard.this) {
                if (closed) return;
                if (!completed) failed();
                closed=true; active--; state.active--; DependencyGuard.this.notifyAll();
            }
        }
    }
}
